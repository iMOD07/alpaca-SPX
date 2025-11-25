package com.mod98.alpaca.spx.bot;

public enum BotState {
    IDLE,
    //There's no preparation or open deal.

    PREPARE_WAITING,
    // We received a picture of the preparation and are waiting for the picture of the entrance.

    IN_TRADE
    // We entered into a deal (currently a single deal).

}
