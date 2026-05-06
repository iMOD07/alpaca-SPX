package com.mod98.alpaca.spx.ibkr.events;

public record OrderStatusEvent(
        int orderId,
        String status,          // PendingSubmit, Submitted, Filled, Cancelled, ApiCancelled, Inactive
        double filled,
        double remaining,
        double avgFillPrice,
        double lastFillPrice,
        String whyHeld
) {
    public boolean isFilled()      { return "Filled".equalsIgnoreCase(status); }
    public boolean isCancelled()   { return "Cancelled".equalsIgnoreCase(status) || "ApiCancelled".equalsIgnoreCase(status); }
    public boolean isInactive()    { return "Inactive".equalsIgnoreCase(status); }
    public boolean isWorking()     { return "Submitted".equalsIgnoreCase(status) || "PreSubmitted".equalsIgnoreCase(status); }
}
