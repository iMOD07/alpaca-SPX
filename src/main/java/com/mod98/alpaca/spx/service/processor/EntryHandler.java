package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealEvent;
import com.mod98.alpaca.spx.domain.DealEventType;
import com.mod98.alpaca.spx.domain.DealStatus;
import com.mod98.alpaca.spx.ibkr.IbkrConnectionManager;
import com.mod98.alpaca.spx.ibkr.IbkrContractService;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.ibkr.IbkrOrderIdService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.ib.client.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * ENTRY الجديد — دخول مباشر بدون PREPARE.
 *
 * 3-Phase Transactional Pattern (يحافظ على الـ pattern الناجح من v1):
 *   Phase A (Tx): إنشاء Deal + حجز orderId + resolve contract
 *   Phase B (No Tx): IBKR placeOrder (network call)
 *   Phase C (Tx): finalize result
 *
 * Safety:
 *  - Duplicate check على telegramMessageId
 *  - tradingEnabled kill switch
 *  - IBKR connectivity check
 *  - Spread/range/slippage guards داخل IBKR layer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryHandler {

    private static final BigDecimal MIN_OPTION_PRICE = new BigDecimal("0.05");

    private final DealRepository dealRepo;
    private final DealEventRepository eventRepo;
    private final IbkrConnectionManager conn;
    private final IbkrExecutionService execution;
    private final IbkrOrderIdService orderIds;
    private final IbkrContractService contractService;
    private final TradingProperties trading;

    public void handle(ParsedSignal signal) {
        // Pre-checks
        if (!trading.isTradingEnabled()) {
            log.warn("ENTRY blocked — kill switch ON | msgId={}", signal.getTelegramMessageId());
            return;
        }
        if (!conn.isConnected()) {
            log.error("ENTRY blocked — IBKR disconnected | msgId={}", signal.getTelegramMessageId());
            return;
        }
        if (signal.getEntryPrice().compareTo(MIN_OPTION_PRICE) <= 0) {
            log.error("ENTRY blocked — invalid price {} | msgId={}",
                    signal.getEntryPrice(), signal.getTelegramMessageId());
            return;
        }

        // Phase A — Tx
        Phase1Result phase1;
        try {
            phase1 = createDealAndReserve(signal);
        } catch (DuplicateException e) {
            log.warn("ENTRY blocked — duplicate messageId | msgId={}", signal.getTelegramMessageId());
            return;
        } catch (Exception e) {
            log.error("Phase A failed | msgId={}", signal.getTelegramMessageId(), e);
            return;
        }
        if (phase1 == null) return;

        // Phase B — No Tx (network call)
        IbkrExecutionService.EntryDecisionResult result;
        try {
            result = execution.placeEntryWithReservedId(
                    phase1.deal, phase1.contract, phase1.reservedOrderId);
        } catch (Exception e) {
            log.error("Phase B failed (deal stays ENTRY_PENDING — watchdog handles) | dealId={}",
                    phase1.deal.getId(), e);
            recordExecutionError(phase1.deal.getId(), e.getMessage());
            return;
        }

        // Phase C — Tx
        finalizeResult(phase1.deal.getId(), result);
    }

    @Transactional
    protected Phase1Result createDealAndReserve(ParsedSignal signal) {
        // Duplicate guard على DB level
        if (dealRepo.findByTelegramMessageId(signal.getTelegramMessageId()).isPresent()) {
            throw new DuplicateException();
        }

        // حسابات TP/SL بناءً على entryPrice (مش preparePrice — لأن ما عاد فيه prepare)
        BigDecimal entry = signal.getEntryPrice();
        BigDecimal tp = entry.add(trading.getTpOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slRaw = entry.subtract(trading.getSlOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl = slRaw.max(MIN_OPTION_PRICE);
        if (sl.compareTo(slRaw) > 0) {
            log.warn("SL clamped from {} to {} | msgId={}", slRaw, sl, signal.getTelegramMessageId());
        }

        // Resolve contract
        String right = "CALL".equalsIgnoreCase(signal.getOptionType()) ? "C" : "P";
        Contract contract;
        try {
            contract = contractService.resolveSpxwOptionContract(
                    signal.getExpiryDate(), signal.getStrike().doubleValue(), right);
        } catch (Exception e) {
            log.error("Contract resolution failed | strike={} expiry={} right={}",
                    signal.getStrike(), signal.getExpiryDate(), right, e);
            throw new RuntimeException("Contract resolution failed", e);
        }

        // Reserve orderId
        int reservedOrderId = orderIds.nextOrderId();

        // Build Deal
        Deal deal = new Deal();
        deal.setSymbol(signal.getSymbol() != null ? signal.getSymbol() : "SPXW");
        deal.setOptionType(signal.getOptionType());
        deal.setStrike(signal.getStrike());
        deal.setExpiryDate(signal.getExpiryDate());
        deal.setEntrySignalPrice(entry);
        deal.setTpPrice(tp);
        deal.setSlPrice(sl);
        deal.setTelegramMessageId(signal.getTelegramMessageId());
        deal.setIbkrEntryOrderId(reservedOrderId);
        deal.setIbkrContractId(contract.conid());
        deal.setSignalReceivedAt(Instant.now());
        deal.setOrderSentAt(Instant.now());
        deal.setStatus(DealStatus.ENTRY_PENDING);

        Deal saved = dealRepo.save(deal);

        DealEvent ev = new DealEvent();
        ev.setDeal(saved);
        ev.setEventType(DealEventType.ENTRY_SIGNAL_RECEIVED);
        ev.setEventPrice(entry);
        ev.setRawMessage(signal.getRawText());
        eventRepo.save(ev);

        return new Phase1Result(saved, contract, reservedOrderId);
    }

    @Transactional
    protected void finalizeResult(Long dealId, IbkrExecutionService.EntryDecisionResult result) {
        Deal deal = dealRepo.findById(dealId).orElseThrow();

        if (!result.placed()) {
            log.warn("ENTRY blocked post-MD | dealId={} reason={} ask={} bid={}",
                    dealId, result.blockReason(), result.ask(), result.bid());

            deal.setStatus(DealStatus.CANCELLED);
            dealRepo.save(deal);

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.ENTRY_BLOCKED_OUT_OF_RANGE);
            ev.setEventPrice(result.ask());
            ev.setRawMessage("reason=" + result.blockReason() + " ask=" + result.ask() + " bid=" + result.bid());
            eventRepo.save(ev);
            return;
        }

        deal.setEntryMinPrice(result.lower());
        deal.setEntryMaxPrice(result.upper());
        deal.setCurrentPrice(result.ask());
        dealRepo.save(deal);

        DealEvent placed = new DealEvent();
        placed.setDeal(deal);
        placed.setEventType(DealEventType.ENTRY_ORDER_PLACED);
        placed.setEventPrice(result.ask());
        eventRepo.save(placed);

        log.info("✅ ENTRY PLACED | dealId={} orderId={} ask={} limit={} tp={} sl={}",
                deal.getId(), deal.getIbkrEntryOrderId(),
                result.ask(), result.upper(), deal.getTpPrice(), deal.getSlPrice());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void recordExecutionError(Long dealId, String reason) {
        Deal deal = dealRepo.findById(dealId).orElse(null);
        if (deal == null) return;
        DealEvent err = new DealEvent();
        err.setDeal(deal);
        err.setEventType(DealEventType.EXECUTION_ERROR);
        err.setRawMessage(reason);
        eventRepo.save(err);
    }

    private record Phase1Result(Deal deal, Contract contract, int reservedOrderId) {}

    private static class DuplicateException extends RuntimeException {
        DuplicateException() { super("duplicate messageId"); }
    }
}
