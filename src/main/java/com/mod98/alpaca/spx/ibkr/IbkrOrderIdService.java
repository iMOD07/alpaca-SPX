package com.mod98.alpaca.spx.ibkr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrOrderIdService {

    private final IbkrApiWrapper wrapper;
    private final AtomicInteger seq = new AtomicInteger(-1);
    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    public int nextOrderId() {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    try {
                        int base = wrapper.nextValidIdFuture().get(10, TimeUnit.SECONDS);
                        seq.set(base);
                        initialized = true;
                        log.info("IBKR orderId seed initialized at {}", base);
                    } catch (Exception e) {
                        throw new RuntimeException("IBKR nextValidId not ready", e);
                    }
                }
            }
        }
        return seq.getAndIncrement();
    }
}
