package com.mod98.alpaca.spx.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.EClientSocket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class IbkrContractService {

    private final IbkrConnectionManager conn;
    private final IbkrApiWrapper wrapper;

    private final AtomicInteger reqSeq = new AtomicInteger(2000);

    public Contract resolveSpxwOptionContract(LocalDate expiry, double strike, String right /* C/P */) {
        if (!conn.isConnected()) throw new IllegalStateException("IBKR not connected");

        Contract c = new Contract();
        c.secType("OPT");
        c.symbol("SPX");               // SPX underlying in IB
        c.currency("USD");
        c.exchange("CBOE");            // ok for SPX options
        c.lastTradeDateOrContractMonth(expiry.format(DateTimeFormatter.BASIC_ISO_DATE)); // yyyyMMdd
        c.strike(strike);
        c.right(right);                // "C" or "P"
        c.multiplier("100");
        c.tradingClass("SPXW");        // weekly class hint

        int reqId = reqSeq.incrementAndGet();
        wrapper.registerContractDetailsFuture(reqId);

        EClientSocket client = conn.getClient();
        client.reqContractDetails(reqId, c);

        try {
            // أول نتيجة
            ContractDetails details = wrapper.contractDetailsFuture(reqId).get(5, TimeUnit.SECONDS);
            // انتظر end (تأمين)
            wrapper.contractDetailsEndFuture(reqId).get(5, TimeUnit.SECONDS);

            // خذ العقد النهائي (فيه conId مضبوط)
            return details.contract();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve contractDetails for SPXW option", e);
        }
    }
}
