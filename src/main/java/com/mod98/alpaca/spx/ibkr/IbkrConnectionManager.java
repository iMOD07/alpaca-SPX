package com.mod98.alpaca.spx.ibkr;

import com.ib.client.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IbkrConnectionManager {

    private final IbkrApiWrapper wrapper;
    private final IbkrProperties props;

    private EClientSocket client;
    private EReaderSignal signal;
    private EReader reader;

    @PostConstruct
    public void connect() {
        signal = new EJavaSignal();
        client = new EClientSocket(wrapper, signal);

        log.info("Connecting to IBKR {}:{} clientId={}", props.getHost(), props.getPort(), props.getClientId());
        client.eConnect(props.getHost(), props.getPort(), props.getClientId());

        reader = new EReader(client, signal);
        reader.start();

        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("IBKR reader.processMsgs error", e);
                }
            }
        }, "ibkr-reader").start();
    }

    @PreDestroy
    public void disconnect() {
        if (client != null && client.isConnected()) {
            client.eDisconnect();
            log.info("Disconnected from IBKR");
        }
    }

    public EClientSocket getClient() {
        return client;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }
}
