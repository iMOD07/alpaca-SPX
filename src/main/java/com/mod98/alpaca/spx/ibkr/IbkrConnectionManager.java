package com.mod98.alpaca.spx.ibkr;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class IbkrConnectionManager {

    private final IbkrApiWrapper wrapper;
    private final IbkrProperties props;

    /**
     * ⚠️ TESTING ONLY — when true, requests delayed (15-min lag) market data.
     * Set via property: ibkr.market-data.delayed-fallback=true
     *
     * NEVER enable this in production with real money. Delayed prices will
     * cause incorrect entry/TP/SL decisions.
     */
    @Value("${ibkr.market-data.delayed-fallback:false}")
    private boolean delayedMarketDataFallback;

    private volatile EClientSocket client;
    private volatile EReaderSignal signal;
    private volatile EReader reader;
    private volatile Thread readerThread;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    @PostConstruct
    public synchronized void connect() {
        if (client != null && client.isConnected()) return;

        signal = new EJavaSignal();
        client = new EClientSocket(wrapper, signal);

        log.info("Connecting to IBKR {}:{} clientId={}",
                props.getHost(), props.getPort(), props.getClientId());
        client.eConnect(props.getHost(), props.getPort(), props.getClientId());

        if (!client.isConnected()) {
            log.error("IBKR connection FAILED — will retry");
            return;
        }

        reader = new EReader(client, signal);
        reader.start();

        readerThread = new Thread(() -> {
            while (client != null && client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("IBKR reader.processMsgs error", e);
                }
            }
            log.warn("IBKR reader thread ended");
        }, "ibkr-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        log.info("IBKR connected ✅");

        // Configure market data type AFTER reader thread is started.
        configureMarketDataType();
    }

    /**
     * Sets market data type — must run AFTER nextValidId is received.
     *
     * Types:
     *   1 = LIVE (default; requires subscription)
     *   2 = FROZEN (last value at market close)
     *   3 = DELAYED (15-min lag; FREE — testing only)
     *   4 = DELAYED-FROZEN
     */
    private void configureMarketDataType() {
        try {
            wrapper.nextValidIdFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Did not receive nextValidId within 5s — proceeding anyway");
        }

        if (client == null || !client.isConnected()) {
            log.warn("Cannot configure market data type — not connected");
            return;
        }

        if (delayedMarketDataFallback) {
            client.reqMarketDataType(3);
            log.warn("⚠️⚠️⚠️ DELAYED MARKET DATA ENABLED (15-min lag)");
            log.warn("    FOR TESTING ONLY — DO NOT use with real money");
            log.warn("    To switch to live: set ibkr.market-data.delayed-fallback=false");
            log.warn("    Subscribe to OPRA + CBOE for production");
        } else {
            client.reqMarketDataType(1);
            log.info("Market data type: LIVE (subscriptions required)");
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void healthCheckAndReconnect() {
        if (shuttingDown.get()) return;
        if (client != null && client.isConnected()) return;
        if (!reconnecting.compareAndSet(false, true)) return;

        try {
            log.warn("IBKR disconnected — attempting reconnect");
            try {
                if (client != null) client.eDisconnect();
            } catch (Exception ignore) {}

            Thread.sleep(2000);
            connect();
        } catch (Exception e) {
            log.error("Reconnect failed", e);
        } finally {
            reconnecting.set(false);
        }
    }

    @PreDestroy
    public synchronized void disconnect() {
        shuttingDown.set(true);
        if (client != null && client.isConnected()) {
            try { client.eDisconnect(); } catch (Exception ignore) {}
            log.info("Disconnected from IBKR");
        }
    }

    public EClientSocket getClient() { return client; }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
