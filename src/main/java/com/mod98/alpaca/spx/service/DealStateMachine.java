package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealStatus;
import org.springframework.stereotype.Component;

@Component
public class DealStateMachine {

    public boolean canTransition(DealStatus from, DealStatus to) {
        if (from == null && to == DealStatus.PREPARE) return true;
        if (from == to) return true; // idempotent

        switch (from) {
            case PREPARE:
                return to == DealStatus.PREPARE          // update
                        || to == DealStatus.CANCELLED
                        || to == DealStatus.ENTRY_PENDING
                        || to == DealStatus.FAILED;
            case ENTRY_PENDING:
                return to == DealStatus.ENTERED          // filled
                        || to == DealStatus.CANCELLED        // rejected/timeout
                        || to == DealStatus.PREPARE          // out-of-range → revert
                        || to == DealStatus.FAILED;
            case ENTERED:
                return to == DealStatus.CLOSED
                        || to == DealStatus.FAILED;
            case CANCELLED:
            case CLOSED:
            case FAILED:
                return false; // terminal
            default:
                return false;
        }
    }

    public void transition(Deal deal, DealStatus to) {
        DealStatus from = deal.getStatus();
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid transition " + from + " → " + to + " on dealId=" + deal.getId());
        }
        deal.setStatus(to);
    }
}
