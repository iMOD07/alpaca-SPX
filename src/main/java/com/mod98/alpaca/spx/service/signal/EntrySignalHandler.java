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
import java.util.Optional;

/**
 * Production EntrySignalHandler:
 *  - Phase A (transactional): يحجز deal + orderId + conId
 *  - Phase B (no tx): يستدعي IBKR (market data + place order)
 *  - Phase C (transactional): يحدّث النتيجة
 *
 * Safety:
 *  - SL clamping (لا يصير ≤ 0)
 *  - prepare-validity يحترم signalReceivedAt (يتجدّد عند UPDATE)
 *  - kill switch + IBKR connectivity check
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntrySignalHandler implements SignalHandler {

    /** Min option price in USD — أصغر من ذلك = invalid order. */
    private static final BigDecimal MIN_OPTION_PRICE = new BigDecimal("0.05");

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
            log.warn("ENTRY ignored — tradingEnabled=false (kill switch)");
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
        if (prep == null) return null;
        if (prep.skip) return prep.deal;

        IbkrExecutionService.EntryDecisionResult result;
        try {
            result = ibkrExecutionService.placeEntryWithReservedId(
                    prep.deal, prep.contract, prep.reservedOrderId);
        } catch (Exception e) {
            // ⚠️ نقطة حساسة: لو الـ exception حصل قبل placeOrder → safe to mark FAILED
            // لو حصل بعد placeOrder → الـ deal ENTRY_PENDING في DB والأمر فعلاً مُرسل
            // لازم نترك لـ OrderTrackingService يتعامل عبر orderStatus callback
            log.error("PLACE_ENTRY FAILED | dealId={} (deal stays ENTRY_PENDING — watchdog will handle)",
                    prep.deal.getId(), e);
            recordExecutionError(prep.deal.getId(), e.getMessage());
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

        // ⚠️ validity check يستخدم آخر activity (createdAt OR signalReceivedAt)
        Instant referenceTime = Optional.ofNullable(prepare.getSignalReceivedAt())
                .orElse(prepare.getCreatedAt());
        Duration age = Duration.between(referenceTime, Instant.now());
        if (age.compareTo(trading.getPrepareValidity()) > 0) {
            log.warn("ENTRY BLOCKED | PREPARE expired | dealId={} ageMin={}",
                    prepare.getId(), age.toMinutes());
            stateMachine.transition(prepare, DealStatus.CANCELLED);
            dealRepository.save(prepare);
            recordBlocked(prepare, DealEventType.CANCELLED, null, "PREPARE expired");
            return new PreFlightResult(prepare, true, 0, null);
        }

        BigDecimal signalPrice = prepare.getPreparePrice();
        if (signalPrice == null || signalPrice.compareTo(MIN_OPTION_PRICE) <= 0) {
            log.error("ENTRY BLOCKED | invalid signal price | dealId={} price={}",
                    prepare.getId(), signalPrice);
            stateMachine.transition(prepare, DealStatus.FAILED);
            dealRepository.save(prepare);
            recordBlocked(prepare, DealEventType.EXECUTION_ERROR, signalPrice, "invalid price");
            return new PreFlightResult(prepare, true, 0, null);
        }

        // ⚠️ TP/SL مع clamping (لمنع SL سالب/أقل من tick)
        BigDecimal tp = signalPrice.add(trading.getTpOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slRaw = signalPrice.subtract(trading.getSlOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl = slRaw.max(MIN_OPTION_PRICE);
        if (sl.compareTo(slRaw) > 0) {
            log.warn("SL clamped from {} to {} (would have been ≤ 0) | dealId={}",
                    slRaw, sl, prepare.getId());
        }

        prepare.setEntrySignalPrice(signalPrice);
        prepare.setTpPrice(tp);
        prepare.setSlPrice(sl);
        prepare.setSignalReceivedAt(Instant.now());

        // Resolve contract داخل preFlight
        String right = "CALL".equalsIgnoreCase(prepare.getOptionType()) ? "C" : "P";
        Contract contract;
        try {
            contract = contractService.resolveSpxwOptionContract(
                    prepare.getExpiryDate(), prepare.getStrike().doubleValue(), right);
        } catch (Exception e) {
            log.error("Contract resolution failed | dealId={} expiry={} strike={} right={}",
                    prepare.getId(), prepare.getExpiryDate(), prepare.getStrike(), right, e);
            stateMachine.transition(prepare, DealStatus.FAILED);
            dealRepository.save(prepare);
            recordBlocked(prepare, DealEventType.EXECUTION_ERROR, null,
                    "Contract resolve failed: " + e.getMessage());
            return new PreFlightResult(prepare, true, 0, null);
        }

        // ⚠️ CRITICAL: حجز orderId و حفظه قبل ما نستدعي placeOrder.
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
            // ارجع لـ PREPARE ليُحاوَل لاحقاً
            stateMachine.transition(deal, DealStatus.PREPARE);
            deal.setIbkrEntryOrderId(null);   // الـ ID لم يُستخدم في IBKR
            deal.setOrderSentAt(null);
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

        log.info("✅ ENTRY PLACED | dealId={} orderId={} ask={} bid={} limit={} tp={} sl={}",
                deal.getId(), deal.getIbkrEntryOrderId(), result.ask(), result.bid(),
                result.upper(), deal.getTpPrice(), deal.getSlPrice());
        return deal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void recordExecutionError(Long dealId, String reason) {
        Deal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) return;
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
