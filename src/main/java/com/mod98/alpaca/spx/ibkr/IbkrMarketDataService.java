package com.mod98.alpaca.spx.ibkr;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.TagValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class IbkrMarketDataService {

    private final IbkrConnectionManager conn;
    private final IbkrApiWrapper wrapper;

    private final AtomicInteger tickerSeq = new AtomicInteger(10_000);

    public double getAskSnapshot(Contract contract, int timeoutMs) {
        if (!conn.isConnected()) throw new IllegalStateException("IBKR not connected");

        int tickerId = tickerSeq.incrementAndGet();
        var askFuture = wrapper.registerAskFuture(tickerId);

        EClientSocket client = conn.getClient();
        List<TagValue> opts = Collections.emptyList();

        // snapshot=true
        client.reqMktData(tickerId, contract, "", true, false, opts);

        try {
            double ask = askFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            client.cancelMktData(tickerId);
            return ask;
        } catch (Exception e) {
            try { client.cancelMktData(tickerId); } catch (Exception ignore) {}
            throw new RuntimeException("ASK snapshot timeout", e);
        }
    }

    public double getBidSnapshot(Contract contract, int timeoutMs) {

        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }

        int tickerId = tickerSeq.incrementAndGet();
        var bidFuture = wrapper.registerBidFuture(tickerId);

        conn.getClient().reqMktData(
                tickerId,
                contract,
                "",
                true,
                false,
                Collections.emptyList()
        );

        try {
            double bid = bidFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            conn.getClient().cancelMktData(tickerId);
            return bid;
        } catch (Exception e) {
            try { conn.getClient().cancelMktData(tickerId); } catch (Exception ignore) {}
            throw new RuntimeException("BID snapshot timeout", e);
        }
    }


}
