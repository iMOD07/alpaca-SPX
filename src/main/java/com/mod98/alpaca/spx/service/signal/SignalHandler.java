package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.Deal;

public interface SignalHandler {
    Deal handle(ParsedSignal signal);
}
