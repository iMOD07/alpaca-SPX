package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.RiskState;
import com.mod98.alpaca.spx.repo.RiskStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Risk Management — v3.1
 *
 * Rules:
 *  - Track consecutive losses
 *  - Reset counter on ANY win (PnL >= 0)
 *  - Activate kill switch after N consecutive losses (default 4)
 *  - Kill switch blocks new entries until MANUAL reset
 *
 * Use:
 *  - EntryHandler.handle() → check isKillSwitchActive() before entry
 *  - OrderTrackingService → call recordOutcome() on every CLOSED deal
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskLimitService {

    private static final int SINGLETON_ID = 1;

    @Value("${spx.trading.max-consecutive-losses:4}")
    private int maxConsecutiveLosses;

    private final RiskStateRepository repo;

    /** Read-only check — used before every entry attempt. */
    @Transactional(readOnly = true)
    public boolean isKillSwitchActive() {
        return loadOrCreate().isKillSwitchActive();
    }

    @Transactional(readOnly = true)
    public int getConsecutiveLosses() {
        return loadOrCreate().getConsecutiveLosses();
    }

    /**
     * Record outcome of a CLOSED deal.
     *
     * @param dealId the deal that just closed
     * @param pnl    profit/loss in dollars (negative = loss)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(Long dealId, BigDecimal pnl) {
        if (pnl == null) {
            log.warn("recordOutcome: null PnL for dealId={} — skipping", dealId);
            return;
        }

        RiskState state = loadOrCreate();
        state.setLastDealId(dealId);
        state.setLastDealPnl(pnl);

        if (pnl.signum() < 0) {
            // Loss — increment counter
            state.setConsecutiveLosses(state.getConsecutiveLosses() + 1);
            log.warn("📉 LOSS recorded | dealId={} pnl=${} consecutive={}/{}",
                    dealId, pnl, state.getConsecutiveLosses(), maxConsecutiveLosses);

            if (state.getConsecutiveLosses() >= maxConsecutiveLosses
                    && !state.isKillSwitchActive()) {
                state.setKillSwitchActive(true);
                state.setKillSwitchActivatedAt(Instant.now());
                state.setKillSwitchReason(
                        maxConsecutiveLosses + " consecutive losses");
                log.error("🛑 KILL SWITCH ACTIVATED | reason={} consecutive={}",
                        state.getKillSwitchReason(), state.getConsecutiveLosses());
            }
        } else {
            // Win or breakeven — reset counter
            int previousLosses = state.getConsecutiveLosses();
            if (previousLosses > 0) {
                log.info("✅ Counter reset on win | dealId={} pnl=${} (was {} losses)",
                        dealId, pnl, previousLosses);
            }
            state.setConsecutiveLosses(0);
        }

        repo.save(state);
    }

    /**
     * Manual reset (run via SQL or admin endpoint after market review).
     * NOT called automatically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void manualReset(String operatorNote) {
        RiskState state = loadOrCreate();
        boolean wasActive = state.isKillSwitchActive();
        state.setConsecutiveLosses(0);
        state.setKillSwitchActive(false);
        state.setKillSwitchActivatedAt(null);
        state.setKillSwitchReason(null);
        repo.save(state);
        log.warn("🔄 RISK STATE MANUALLY RESET | wasActive={} note={}",
                wasActive, operatorNote);
    }

    private RiskState loadOrCreate() {
        return repo.findById(SINGLETON_ID).orElseGet(() -> {
            RiskState fresh = new RiskState();
            fresh.setId(SINGLETON_ID);
            fresh.setConsecutiveLosses(0);
            fresh.setKillSwitchActive(false);
            return repo.save(fresh);
        });
    }
}
