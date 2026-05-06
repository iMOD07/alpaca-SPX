package com.mod98.alpaca.spx.ibkr;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class IbkrConnectionManager {

    private final IbkrApiWrapper wrapper;
    private final IbkrProperties props;

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

        log.info("Connecting to IBKR {}:{} clientId={}", props.getHost(), props.getPort(), props.getClientId());
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
    }

    /**
     * كل 10 ثوان: لو الاتصال ضاع، أعد المحاولة.
     * IbkrApiWrapper.connectionClosed() ينشر event، لكن نضيف polling كـ safety net.
     */
    @Scheduled(fixedDelay = 10_000)
    public void healthCheckAndReconnect() {
        if (shuttingDown.get()) return;
        if (client != null && client.isConnected()) return;
        if (!reconnecting.compareAndSet(false, true)) return;

        try {
            log.warn("IBKR disconnected — attempting reconnect");
            // tear down old client
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
