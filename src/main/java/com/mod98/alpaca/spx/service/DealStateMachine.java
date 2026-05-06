// src/main/java/com/mod98/alpaca/spx/service/DealStateMachine.java
package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.Deal;
import com.mod98.alpaca.spx.domain.DealStatus;
import org.springframework.stereotype.Component;

@Component
public class DealStateMachine {

    public boolean canTransition(DealStatus from, DealStatus to) {
        if (from == null && to == DealStatus.PREPARE) {
            return true; // NONE -> PREPARE
        }

        switch (from) {
            case PREPARE:
                return to == DealStatus.PREPARE   // Order update
                        || to == DealStatus.CANCELLED
                        || to == DealStatus.ENTERED;
            case ENTERED:
                return to == DealStatus.CLOSED;    // TP/SL Hit or Manual Exit
            default:
                return false;
        }
    }

    public void transition(Deal deal, DealStatus to) {
        DealStatus from = deal.getStatus();
        if (!canTransition(from, to)) {
            // Protection only – don't change the logical rules I wrote.
            throw new IllegalStateException("Invalid state transition " + from + " -> " + to);
        }
        deal.setStatus(to);
    }
}
