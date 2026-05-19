package com.mod98.alpaca.spx.ibkr.events;

/**
 * Spring event published when IBKR returns accountSummary callback.
 * Consumed by IbkrAccountSummaryService.
 */
public record AccountSummaryEvent(
        int reqId,
        String account,
        String tag,
        String value,
        String currency
) {}
