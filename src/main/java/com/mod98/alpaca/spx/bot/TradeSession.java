package com.mod98.alpaca.spx.bot;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class TradeSession {

    // Time of arrival of the preparation image
    private Instant prepareAt;

    // Entry time
    private Instant entryAt;

    // Entry price
    private Double entryPrice;

    // Last updated price
    private Double lastUpdatedPrice;

    // AI feedback (optional)
    private String notes;

    public void reset() {
        prepareAt = null;
        entryAt = null;
        entryPrice = null;
        lastUpdatedPrice = null;
        notes = null;
    }
}
