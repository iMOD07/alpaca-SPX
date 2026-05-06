package com.mod98.alpaca.spx.ibkr;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class IbkrOrderIdService {

    private final IbkrApiWrapper wrapper;
    private final AtomicInteger seq = new AtomicInteger(-1);

    public int nextOrderId() {
        try {
            if (seq.get() < 0) {
                int base = wrapper.nextValidIdFuture().get(5, TimeUnit.SECONDS);
                seq.set(base);
            }
            return seq.getAndIncrement();
        } catch (Exception e) {
            throw new RuntimeException("IBKR nextValidId not ready", e);
        }
    }
}
