package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * State machine الجديد (بدون PREPARE).
 *
 *                ┌──→ ENTERED ──→ CLOSED
 *                │                │
 *  ENTRY_PENDING ┤                └→ FAILED
 *                │
 *                ├──→ CANCELLED (terminal)
 *                │
 *                └──→ FAILED (terminal)
 *
 * Rules:
 *  - First state is always ENTRY_PENDING (when Deal is created)
 *  - Self-transition allowed (idempotent)
 *  - Terminal states: CLOSED, CANCELLED, FAILED
 */
@Slf4j
@Component
public class DealStateMachine {

    public boolean canTransition(DealStatus from, DealStatus to) {
        if (from == null && to == DealStatus.ENTRY_PENDING) return true;
        if (from == to) return true; // idempotent

        return switch (from) {
            case ENTRY_PENDING -> to == DealStatus.ENTERED
                    || to == DealStatus.CANCELLED
                    || to == DealStatus.FAILED;
            case ENTERED -> to == DealStatus.CLOSED
                    || to == DealStatus.FAILED;
            // terminals — لا انتقال
            case CANCELLED, CLOSED, FAILED -> false;
        };
    }

    public void transition(Deal deal, DealStatus to) {
        DealStatus from = deal.getStatus();
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(
                    "Invalid transition " + from + " → " + to + " on dealId=" + deal.getId());
        }
        log.info("STATE | dealId={} {} → {}", deal.getId(), from, to);
        deal.setStatus(to);
    }

    public static class IllegalStateTransitionException extends IllegalStateException {
        public IllegalStateTransitionException(String msg) { super(msg); }
    }
}
