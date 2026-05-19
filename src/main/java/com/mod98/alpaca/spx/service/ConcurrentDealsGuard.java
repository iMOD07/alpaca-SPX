package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.DealStatus;
import com.mod98.alpaca.spx.repo.DealRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Concurrent deals limit guard — v3.2.
 *
 * Rule: bot may NOT open more than {@code maxConcurrentDeals} open positions
 * at the same time. "Open" = ENTRY_PENDING or ENTERED.
 *
 * Once OPRA position is CLOSED/CANCELLED/FAILED → it no longer counts.
 *
 * Why: prevents capital blowout in a fast-signal scenario (5 PREPs in 10 min).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcurrentDealsGuard {

    @Value("${spx.trading.max-concurrent-deals:3}")
    private int maxConcurrentDeals;

    private final DealRepository dealRepository;

    /**
     * @return true if a new deal CAN be opened, false otherwise.
     */
    @Transactional(readOnly = true)
    public boolean canOpenNew() {
        int active = countActive();
        boolean allowed = active < maxConcurrentDeals;
        if (!allowed) {
            log.warn("🚫 ConcurrentDealsGuard: limit reached | active={} max={}",
                    active, maxConcurrentDeals);
        }
        return allowed;
    }

    @Transactional(readOnly = true)
    public int countActive() {
        List<DealStatus> activeStatuses = List.of(
                DealStatus.ENTRY_PENDING,
                DealStatus.ENTERED
        );
        return dealRepository.findByStatusIn(activeStatuses).size();
    }

    public int getMaxConcurrentDeals() {
        return maxConcurrentDeals;
    }
}
