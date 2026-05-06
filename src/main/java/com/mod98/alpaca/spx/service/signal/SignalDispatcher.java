// src/main/java/com/mod98/alpaca/spx/service/signal/SignalDispatcher.java
package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

@Service
public class SignalDispatcher {

    private final Map<SignalType, SignalHandler> handlers = new EnumMap<>(SignalType.class);

    public SignalDispatcher(
            PrepareSignalHandler prepareHandler,
            EntrySignalHandler entryHandler,
            CancelSignalHandler cancelHandler,
            UpdateOrderSignalHandler updateHandler,
            PriceAlertSignalHandler priceAlertHandler,
            ManualExitHandler manualExitHandler
            // You can add ManualExitHandler later.
    ) {
        handlers.put(SignalType.PREPARE, prepareHandler);
        handlers.put(SignalType.PREPARE_WITH_DATE, prepareHandler);
        handlers.put(SignalType.ENTRY, entryHandler);
        handlers.put(SignalType.CANCEL, cancelHandler);
        handlers.put(SignalType.UPDATE, updateHandler);
        handlers.put(SignalType.PRICE_ALERT, priceAlertHandler);
        handlers.put(SignalType.EXIT, manualExitHandler);
        // EXIT → ManualExitHandler
    }

    public void dispatch(ParsedSignal signal) {
        SignalHandler handler = handlers.get(signal.getSignalType());
        if (handler != null) {
            handler.handle(signal);
        }
    }

}
