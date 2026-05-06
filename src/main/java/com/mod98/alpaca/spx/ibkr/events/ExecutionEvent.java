package com.mod98.alpaca.spx.ibkr.events;

public record ExecutionEvent(
        int orderId,
        String execId,
        double price,
        double quantity,
        int conId
) {}
