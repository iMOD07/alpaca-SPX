package com.mod98.alpaca.spx.ibkr;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.TagValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snapshot market data for SPX/SPXW options.
 *
 * <p>Uses STREAMING mode (not snapshot=true) because:
 *  - Delayed market data has multi-step handshake (error 10090 → marketDataType → error 10167 → tick)
 *  - Snapshot mode sometimes closes ticker before delayed tick arrives
 *  - Streaming mode + manual cancel is more reliable
 *
 * <p>Both fields handled in IbkrApiWrapper.tickPrice():
 *  - Live:    field 1=BID, 2=ASK
 *  - Delayed: field 66=BID, 67=ASK
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrMarketDataService {

    private final IbkrConnectionManager conn;
    private final IbkrApiWrapper wrapper;

    private final AtomicInteger tickerSeq = new AtomicInteger(10_000);

    public double getAskSnapshot(Contract contract, int timeoutMs) {
        return getSnapshot(contract, timeoutMs, true);
    }

    public double getBidSnapshot(Contract contract, int timeoutMs) {
        return getSnapshot(contract, timeoutMs, false);
    }

    private double getSnapshot(Contract contract, int timeoutMs, boolean wantAsk) {
        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }

        int tickerId = tickerSeq.incrementAndGet();
        CompletableFuture<Double> future = wantAsk
                ? wrapper.registerAskFuture(tickerId)
                : wrapper.registerBidFuture(tickerId);

        EClientSocket client = conn.getClient();
        List<TagValue> opts = Collections.emptyList();

        try {
            // ⚠️ snapshot=false (streaming) — gives delayed-data handshake time to complete
            // ⚠️ regulatorySnapshot=false
            client.reqMktData(tickerId, contract, "", false, false, opts);
            log.debug("reqMktData | tickerId={} conId={} want={}",
                    tickerId, contract.conid(), wantAsk ? "ASK" : "BID");

            double price = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.debug("Got {} = {} | tickerId={}", wantAsk ? "ASK" : "BID", price, tickerId);
            return price;
        } catch (Exception e) {
            String type = wantAsk ? "ASK" : "BID";
            throw new RuntimeException(type + " snapshot timeout (tickerId=" + tickerId + ")", e);
        } finally {
            // Always cancel + unregister regardless of success/failure
            try {
                client.cancelMktData(tickerId);
            } catch (Exception ignored) {}
            wrapper.unregisterAskBid(tickerId);
        }
    }
}
