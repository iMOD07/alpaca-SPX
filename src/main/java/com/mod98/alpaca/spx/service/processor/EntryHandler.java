package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.*;
import com.mod98.alpaca.spx.ibkr.IbkrConnectionManager;
import com.mod98.alpaca.spx.ibkr.IbkrContractService;
import com.mod98.alpaca.spx.ibkr.IbkrExecutionService;
import com.mod98.alpaca.spx.ibkr.IbkrOrderIdService;
import com.mod98.alpaca.spx.repo.DealEventRepository;
import com.mod98.alpaca.spx.repo.DealRepository;
import com.mod98.alpaca.spx.repo.PendingEntryRepository;
import com.mod98.alpaca.spx.service.DealStateMachine;
import com.mod98.alpaca.spx.service.RiskLimitService;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.ib.client.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.List;

/**
 * EntryHandler v3.1 — PREP-then-ENTRY workflow with risk management.
 *
 * Pre-flight checks (in order):
 *   1. tradingEnabled (config kill switch)
 *   2. risk kill switch (4 consecutive losses)
 *   3. IBKR connected
 *   4. Market hours (before 4 PM ET)
 *
 * Then: find matching PREP → 3-phase transactional flow.
 *
 * Changes from v3:
 *   ✅ Risk kill switch check
 *   ✅ All status changes via DealStateMachine (no bypass)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryHandler {

    private static final LocalTime MARKET_CLOSE_ET = LocalTime.of(16, 0);
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final BigDecimal MIN_OPTION_PRICE = new BigDecimal("0.05");

    private final PendingEntryRepository pendingRepo;
    private final DealRepository dealRepo;
    private final DealEventRepository eventRepo;
    private final IbkrConnectionManager conn;
    private final IbkrExecutionService execution;
    private final IbkrOrderIdService orderIds;
    private final IbkrContractService contractService;
    private final TradingProperties trading;
    private final DealStateMachine stateMachine;
    private final RiskLimitService riskLimit;

    public void handle(ParsedSignal signal) {
        // 1) Config kill switch
        if (!trading.isTradingEnabled()) {
            log.warn("ENTRY blocked — trading disabled in config | msgId={}",
                    signal.getTelegramMessageId());
            return;
        }

        // 2) Risk kill switch (4 consecutive losses)
        if (riskLimit.isKillSwitchActive()) {
            log.error("🛑 ENTRY blocked — RISK KILL SWITCH ACTIVE | consecutive={} | msgId={}",
                    riskLimit.getConsecutiveLosses(), signal.getTelegramMessageId());
            return;
        }

        // 3) IBKR connectivity
        if (!conn.isConnected()) {
            log.error("ENTRY blocked — IBKR disconnected | msgId={}",
                    signal.getTelegramMessageId());
            return;
        }

        // 4) Market hours
        ZonedDateTime nowET = ZonedDateTime.now(ET_ZONE);
        if (nowET.toLocalTime().isAfter(MARKET_CLOSE_ET)) {
            log.warn("ENTRY blocked — after market close ET | msgId={}",
                    signal.getTelegramMessageId());
            return;
        }

        // Find matching PREP (today, same option type, newest first)
        Instant startOfTodayET = nowET.toLocalDate().atStartOfDay(ET_ZONE).toInstant();
        List<PendingEntry> candidates = pendingRepo
                .findActiveByTypeOrderByNewestFirst(signal.getOptionType(), startOfTodayET);

        if (candidates.isEmpty()) {
            log.warn("⚠️ ENTRY skipped — no active PREP for {} today | msgId={}",
                    signal.getOptionType(), signal.getTelegramMessageId());
            return;
        }

        PendingEntry prep = candidates.get(0);
        log.info("✅ Matched PREP | prepId={} prepMsgId={} type={} strike={} price={} expiry={}",
                prep.getId(), prep.getTelegramMessageId(),
                prep.getOptionType(), prep.getStrike(),
                prep.getEntryPrice(), prep.getExpiryDate());

        // Phase A — atomic: create deal + consume PREP
        Phase1Result phase1;
        try {
            phase1 = createDealAndConsumePrep(prep, signal);
        } catch (DuplicateException e) {
            log.warn("ENTRY blocked — duplicate messageId | msgId={}",
                    signal.getTelegramMessageId());
            return;
        } catch (Exception e) {
            log.error("Phase A failed | msgId={}", signal.getTelegramMessageId(), e);
            return;
        }
        if (phase1 == null) return;

        // Phase B — IBKR call (no transaction; long-running)
        IbkrExecutionService.EntryDecisionResult result;
        try {
            result = execution.placeEntryWithReservedId(
                    phase1.deal, phase1.contract, phase1.reservedOrderId);
        } catch (Exception e) {
            log.error("Phase B failed | dealId={}", phase1.deal.getId(), e);
            recordExecutionError(phase1.deal.getId(), e.getMessage());
            transitionToFailed(phase1.deal.getId());
            return;
        }

        // Phase C — atomic: finalize
        finalizeResult(phase1.deal.getId(), result);
    }

    @Transactional
    protected Phase1Result createDealAndConsumePrep(PendingEntry prep, ParsedSignal signal) {
        if (dealRepo.findByTelegramMessageId(signal.getTelegramMessageId()).isPresent()) {
            throw new DuplicateException();
        }

        BigDecimal entry = prep.getEntryPrice();
        BigDecimal tp = entry.add(trading.getTpOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slRaw = entry.subtract(trading.getSlOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sl = slRaw.max(MIN_OPTION_PRICE);

        String right = "CALL".equalsIgnoreCase(prep.getOptionType()) ? "C" : "P";
        Contract contract;
        try {
            contract = contractService.resolveSpxwOptionContract(
                    prep.getExpiryDate(), prep.getStrike().doubleValue(), right);
        } catch (Exception e) {
            log.error("Contract resolution failed | strike={} expiry={} right={}",
                    prep.getStrike(), prep.getExpiryDate(), right, e);
            throw new RuntimeException("Contract resolution failed", e);
        }

        int reservedOrderId = orderIds.nextOrderId();

        // Build Deal (status will be set via state machine)
        Deal deal = new Deal();
        deal.setSymbol("SPXW");
        deal.setOptionType(prep.getOptionType());
        deal.setStrike(prep.getStrike());
        deal.setExpiryDate(prep.getExpiryDate());
        deal.setEntrySignalPrice(entry);
        deal.setTpPrice(tp);
        deal.setSlPrice(sl);
        deal.setTelegramMessageId(signal.getTelegramMessageId());
        deal.setIbkrEntryOrderId(reservedOrderId);
        deal.setIbkrContractId(contract.conid());
        deal.setSignalReceivedAt(Instant.now());
        deal.setOrderSentAt(Instant.now());

        // ✅ Use state machine (from null → ENTRY_PENDING)
        stateMachine.transition(deal, DealStatus.ENTRY_PENDING);
        Deal saved = dealRepo.save(deal);

        // Atomically consume PREP
        prep.markConsumed("matched_entry", saved.getId());
        pendingRepo.save(prep);

        // Audit event
        DealEvent ev = new DealEvent();
        ev.setDeal(saved);
        ev.setEventType(DealEventType.ENTRY_SIGNAL_RECEIVED);
        ev.setEventPrice(entry);
        ev.setRawMessage("Matched PREP id=" + prep.getId()
                + " (msgId=" + prep.getTelegramMessageId() + ")");
        eventRepo.save(ev);

        return new Phase1Result(saved, contract, reservedOrderId);
    }

    @Transactional
    protected void finalizeResult(Long dealId, IbkrExecutionService.EntryDecisionResult result) {
        Deal deal = dealRepo.findById(dealId).orElseThrow();

        if (!result.placed()) {
            log.warn("ENTRY blocked post-MD | dealId={} reason={}",
                    dealId, result.blockReason());
            // ✅ Use state machine instead of direct setStatus
            stateMachine.transition(deal, DealStatus.CANCELLED);
            dealRepo.save(deal);

            DealEvent ev = new DealEvent();
            ev.setDeal(deal);
            ev.setEventType(DealEventType.ENTRY_BLOCKED_OUT_OF_RANGE);
            ev.setEventPrice(result.ask());
            ev.setRawMessage("reason=" + result.blockReason());
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
    protected void transitionToFailed(Long dealId) {
        try {
            Deal deal = dealRepo.findById(dealId).orElse(null);
            if (deal == null) return;
            stateMachine.transition(deal, DealStatus.FAILED);
            dealRepo.save(deal);
        } catch (Exception e) {
            log.error("Failed to mark deal as FAILED | dealId={}", dealId, e);
        }
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
