package com.mod98.alpaca.spx.service.signal;

import com.ib.client.Contract;
import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrConnectionManager;
import com.mod98.alpaca.spx.ibkr.IbkrContractService;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.ibkr.IbkrOrderIdService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * يمنع race condition مع OrderTrackingService:
 *  - Phase A (transactional): يحجز الـ deal في ENTRY_PENDING ويحفظ orderId+conId
 *  - Phase B (no tx): يستدعي IBKR لإرسال الأمر
 *  - Phase C (transactional): يحدّث النتيجة
 *
 * بهذي الطريقة، أي callback من IBKR يجد orderId محفوظاً بالفعل في DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntrySignalHandler implements SignalHandler {

    private final DealRepository dealRepository;
    private final DealEventRepository eventRepository;
    private final IbkrExecutionService ibkrExecutionService;
    private final IbkrOrderIdService orderIds;
    private final IbkrContractService contractService;
    private final IbkrConnectionManager conn;
    private final TradingProperties trading;
    private final DealStateMachine stateMachine;

    @Override
    public Deal handle(ParsedSignal signal) {
        if (!trading.isTradingEnabled()) {
            log.warn("ENTRY ignored — tradingEnabled=false");
            return null;
        }
        if (!conn.isConnected()) {
            log.error("ENTRY BLOCKED — IBKR disconnected | msgId={}", signal.getTelegramMessageId());
            return null;
        }

        PreFlightResult prep;
        try {
            prep = preFlight(signal);
        } catch (Exception e) {
            log.error("PREFLIGHT FAILED | msgId={}", signal.getTelegramMessageId(), e);
            return null;
        }
        if (prep == null || prep.skip) return prep == null ? null : prep.deal;

        IbkrExecutionService.EntryDecisionResult result;
        try {
            result = ibkrExecutionService.placeEntryWithReservedId(
                    prep.deal, prep.contract, prep.reservedOrderId);
        } catch (Exception e) {
            log.error("PLACE_ENTRY FAILED | dealId={}", prep.deal.getId(), e);
            markFailed(prep.deal.getId(), e.getMessage());
            return prep.deal;
        }

        return finalizeAfterPlace(prep.deal.getId(), result);
    }

    @Transactional
    protected PreFlightResult preFlight(ParsedSignal signal) {
        Deal prepare = findMatchingPrepare(signal);
        if (prepare == null) {
            log.warn("ENTRY BLOCKED | no matching PREPARE");
            recordBlocked(null, DealEventType.ENTRY_BLOCKED_NO_PREPARE,
                    signal.getEntrySignalPrice(), signal.getRawText());
            return null;
        }

        if (prepare.getStatus() != DealStatus.PREPARE) {
            log.warn("ENTRY BLOCKED | already processed | dealId={} status={}",
                    prepare.getId(), prepare.getStatus());
            recordBlocked(prepare, DealEventType.ENTRY_BLOCKED_DUPLICATE, null, signal.getRawText());
            return new PreFlightResult(prepare, true, 0, null);
        }

        Duration age = Duration.between(prepare.getCreatedAt(), Instant.now());
        if (age.compareTo(trading.getPrepareValidity()) > 0) {
            log.warn("ENTRY BLOCKED | PREPARE expired | dealId={}", prepare.getId());
            stateMachine.transition(prepare, DealStatus.CANCELLED);
            dealRepository.save(prepare);
            recordBlocked(prepare, DealEventType.CANCELLED, null, "PREPARE expired");
            return new PreFlightResult(prepare, true, 0, null);
        }

        BigDecimal signalPrice = prepare.getPreparePrice();
        prepare.setEntrySignalPrice(signalPrice);
        prepare.setTpPrice(signalPrice.add(trading.getTpOffset()).setScale(2, RoundingMode.HALF_UP));
        prepare.setSlPrice(signalPrice.subtract(trading.getSlOffset()).setScale(2, RoundingMode.HALF_UP));
        prepare.setSignalReceivedAt(Instant.now());

        String right = "CALL".equalsIgnoreCase(prepare.getOptionType()) ? "C" : "P";
        Contract contract = contractService.resolveSpxwOptionContract(
                prepare.getExpiryDate(), prepare.getStrike().doubleValue(), right);

        // CRITICAL: حجز orderId و حفظه قبل ما نستدعي placeOrder.
        int reservedOrderId = orderIds.nextOrderId();

        prepare.setIbkrEntryOrderId(reservedOrderId);
        prepare.setIbkrContractId(contract.conid());
        prepare.setOrderSentAt(Instant.now());
        stateMachine.transition(prepare, DealStatus.ENTRY_PENDING);
        Deal saved = dealRepository.save(prepare);

        DealEvent received = new DealEvent();
        received.setDeal(saved);
        received.setEventType(DealEventType.ENTRY_SIGNAL_RECEIVED);
        received.setEventPrice(signalPrice);
        received.setRawMessage(signal.getRawText());
        eventRepository.save(received);

        return new PreFlightResult(saved, false, reservedOrderId, contract);
    }

    @Transactional
    protected Deal finalizeAfterPlace(Long dealId, IbkrExecutionService.EntryDecisionResult result) {
        Deal deal = dealRepository.findById(dealId).orElseThrow();

        if (!result.placed()) {
            log.warn("ENTRY BLOCKED post-MD | dealId={} reason={} ask={} bid={}",
                    deal.getId(), result.blockReason(), result.ask(), result.bid());
            stateMachine.transition(deal, DealStatus.PREPARE);
            deal.setIbkrEntryOrderId(null);
            dealRepository.save(deal);
            recordBlocked(deal, DealEventType.ENTRY_BLOCKED_OUT_OF_RANGE,
                    result.ask(), "reason=" + result.blockReason());
            return deal;
        }

        deal.setEntryMinPrice(result.lower());
        deal.setEntryMaxPrice(result.upper());
        deal.setCurrentPrice(result.ask());
        dealRepository.save(deal);

        DealEvent placed = new DealEvent();
        placed.setDeal(deal);
        placed.setEventType(DealEventType.ENTRY_ORDER_PLACED);
        placed.setEventPrice(result.ask());
        eventRepository.save(placed);

        log.info("ENTRY PLACED | dealId={} orderId={} ask={} limit={} tp={} sl={}",
                deal.getId(), deal.getIbkrEntryOrderId(), result.ask(), result.upper(),
                deal.getTpPrice(), deal.getSlPrice());
        return deal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(Long dealId, String reason) {
        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) return;
        if (deal.getStatus() == DealStatus.ENTRY_PENDING) {
            stateMachine.transition(deal, DealStatus.FAILED);
            dealRepository.save(deal);
        }
        DealEvent err = new DealEvent();
        err.setDeal(deal);
        err.setEventType(DealEventType.EXECUTION_ERROR);
        err.setRawMessage(reason);
        eventRepository.save(err);
    }

    private Deal findMatchingPrepare(ParsedSignal signal) {
        if (signal.getReplyToMessageId() != null) {
            Deal d = dealRepository.findByTelegramMessageId(signal.getReplyToMessageId()).orElse(null);
            if (d != null && d.getStatus() == DealStatus.PREPARE) return d;
        }
        if (signal.getStrike() != null && signal.getOptionType() != null && signal.getExpiryDate() != null) {
            List<Deal> c = dealRepository
                    .findByStatusAndSymbolAndStrikeAndOptionTypeAndExpiryDateOrderByCreatedAtDesc(
                            DealStatus.PREPARE,
                            signal.getSymbol() != null ? signal.getSymbol() : "SPXW",
                            signal.getStrike(), signal.getOptionType(), signal.getExpiryDate());
            if (!c.isEmpty()) return c.get(0);
        }
        List<Deal> open = dealRepository.findByStatusOrderByCreatedAtDesc(DealStatus.PREPARE);
        return open.stream().max(Comparator.comparing(Deal::getCreatedAt)).orElse(null);
    }

    private void recordBlocked(Deal deal, DealEventType type, BigDecimal price, String raw) {
        DealEvent e = new DealEvent();
        e.setDeal(deal);
        e.setEventType(type);
        e.setEventPrice(price);
        e.setRawMessage(raw);
        eventRepository.save(e);
    }

    private record PreFlightResult(Deal deal, boolean skip, int reservedOrderId, Contract contract) {}
}
