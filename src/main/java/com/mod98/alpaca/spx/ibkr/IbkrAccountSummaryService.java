package com.mod98.alpaca.spx.ibkr;

import com.mod98.alpaca.spx.ibkr.events.AccountSummaryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Requests account values (BuyingPower) from IBKR via reqAccountSummary.
 *
 * Usage:
 *   double bp = accountSummary.getBuyingPower(5000); // 5s timeout
 *
 * Implementation:
 *   1. Generate unique reqId
 *   2. Send reqAccountSummary("All", "BuyingPower")
 *   3. Wait on CompletableFuture for accountSummary callback
 *   4. cancelAccountSummary to free reqId
 *
 * Note: IBKR caches account values — multiple calls within seconds are cheap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrAccountSummaryService {

    private static final AtomicInteger REQ_ID_COUNTER = new AtomicInteger(9000);
    private static final String TAG_BUYING_POWER = "BuyingPower";

    private final IbkrConnectionManager conn;

    /** reqId → future<value> */
    private final ConcurrentHashMap<Integer, CompletableFuture<BigDecimal>> pending = new ConcurrentHashMap<>();

    /**
     * Get current BuyingPower in account currency (USD).
     * Blocks up to timeoutMs.
     *
     * @return BuyingPower as BigDecimal, or null if timeout/error.
     */
    public BigDecimal getBuyingPower(int timeoutMs) {
        if (!conn.isConnected()) {
            log.warn("AccountSummary: IBKR disconnected — cannot fetch BuyingPower");
            return null;
        }

        int reqId = REQ_ID_COUNTER.incrementAndGet();
        CompletableFuture<BigDecimal> future = new CompletableFuture<>();
        pending.put(reqId, future);

        try {
            conn.getClient().reqAccountSummary(reqId, "All", TAG_BUYING_POWER);
            BigDecimal value = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("AccountSummary | reqId={} BuyingPower=${}", reqId, value);
            return value;
        } catch (Exception e) {
            log.error("AccountSummary failed | reqId={} timeoutMs={}", reqId, timeoutMs, e);
            return null;
        } finally {
            pending.remove(reqId);
            try {
                conn.getClient().cancelAccountSummary(reqId);
            } catch (Exception ignored) {}
        }
    }

    /** Receives accountSummary callback from IbkrApiWrapper via Spring event. */
    @EventListener
    public void onAccountSummary(AccountSummaryEvent ev) {
        if (!TAG_BUYING_POWER.equals(ev.tag())) return;

        CompletableFuture<BigDecimal> future = pending.get(ev.reqId());
        if (future == null) {
            log.debug("AccountSummary callback for unknown reqId={} — ignored", ev.reqId());
            return;
        }
        try {
            BigDecimal value = new BigDecimal(ev.value());
            future.complete(value);
        } catch (NumberFormatException e) {
            log.warn("AccountSummary value not parseable: tag={} value={}", ev.tag(), ev.value());
            future.completeExceptionally(e);
        }
    }
}
