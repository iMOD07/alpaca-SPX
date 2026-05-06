package com.mod98.alpaca.spx.domain;

public enum DealEventType {

    PREPARE_CREATED,
    PREPARE_UPDATED,
    ENTRY_SIGNAL_RECEIVED,
    ENTRY_FILLED,
    TP_PLACED,
    SL_PLACED,
    PRICE_ALERT,
    CANCELLED,
    TP_HIT,
    SL_HIT,
    MANUAL_EXIT,
    RECOVERY_START,
    RECOVERY_DONE
}