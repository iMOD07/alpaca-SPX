package com.mod98.alpaca.spx.ibkr;

import com.ib.client.*;
import com.ib.client.protobuf.ErrorMessageProto;
import com.ib.client.protobuf.ExecutionDetailsEndProto;
import com.ib.client.protobuf.ExecutionDetailsProto;
import com.ib.client.protobuf.OpenOrderProto;
import com.ib.client.protobuf.OpenOrdersEndProto;
import com.ib.client.protobuf.OrderStatusProto;
import com.mod98.alpaca.spx.ibkr.events.ExecutionEvent;
import com.mod98.alpaca.spx.ibkr.events.OrderStatusEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class IbkrApiWrapper implements EWrapper {

    private final ApplicationEventPublisher events;

    public IbkrApiWrapper(ApplicationEventPublisher events) {
        this.events = events;
    }

    // ===== Snapshot futures =====
    private final CompletableFuture<Integer> nextValidIdFuture = new CompletableFuture<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Double>> askFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Double>> bidFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<ContractDetails>> contractDetailsFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<Void>> contractDetailsEndFutures = new ConcurrentHashMap<>();

    public CompletableFuture<Integer> nextValidIdFuture() { return nextValidIdFuture; }

    public CompletableFuture<Double> registerAskFuture(int tickerId) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        askFutures.put(tickerId, f);
        return f;
    }
    public CompletableFuture<Double> registerBidFuture(int tickerId) {
        CompletableFuture<Double> f = new CompletableFuture<>();
        bidFutures.put(tickerId, f);
        return f;
    }
    public void unregisterAskBid(int tickerId) {
        askFutures.remove(tickerId);
        bidFutures.remove(tickerId);
    }
    public void registerContractDetailsFuture(int reqId) {
        contractDetailsFutures.put(reqId, new CompletableFuture<>());
        contractDetailsEndFutures.put(reqId, new CompletableFuture<>());
    }
    public CompletableFuture<ContractDetails> contractDetailsFuture(int reqId) {
        return contractDetailsFutures.get(reqId);
    }
    public CompletableFuture<Void> contractDetailsEndFuture(int reqId) {
        return contractDetailsEndFutures.get(reqId);
    }
    public void unregisterContractDetails(int reqId) {
        contractDetailsFutures.remove(reqId);
        contractDetailsEndFutures.remove(reqId);
    }

    // ===== Callbacks =====

    /** ⚠️ EWrapper is an interface — do NOT call super.nextValidId(). */
    @Override
    public void nextValidId(int orderId) {
        log.info("IBKR nextValidId={}", orderId);
        if (!nextValidIdFuture.isDone()) {
            nextValidIdFuture.complete(orderId);
        }
    }

    @Override
    public void connectionClosed() {
        log.error("IBKR CONNECTION CLOSED");
        events.publishEvent(new ConnectionClosedEvent());
    }

    /**
     * IBKR Error Code Categories:
     *   - INFO (silent):           2103, 2104, 2105, 2106, 2107, 2108, 2150, 2158
     *   - DELAYED-DATA WARNINGS:   10090, 10167, 10168   ← NOT fatal! data still arrives
     *   - CONTRACT/MD FATAL:       200, 354
     *   - GENERAL FARM RECONNECT:  2119                    (warning only)
     *
     * Critical fix: 10090/10167/10168 mean "switching to delayed data" — the delayed
     * price will arrive in tickPrice() within a few ms. Do NOT break the ASK/BID future.
     */
    @Override
    public void error(int id, long time, int code, String msg, String advancedReject) {
        // Pure info codes — silent
        if (code == 2103 || code == 2104 || code == 2105 || code == 2106
                || code == 2107 || code == 2108 || code == 2150 || code == 2158) {
            log.debug("IB INFO | code={} msg={}", code, msg);
            return;
        }

        // Delayed-data warnings — log & DO NOT break futures
        // The actual delayed tick will follow shortly via tickPrice() field 66/67.
        if (code == 10090 || code == 10167 || code == 10168) {
            log.warn("IB DELAYED-DATA NOTICE | id={} code={} msg={}", id, code, msg);
            // intentionally do NOT touch askFutures/bidFutures — let delayed tick complete them
            return;
        }

        // Market-data farm reconnecting — transient, log only
        if (code == 2119) {
            log.warn("IB FARM RECONNECT | id={} msg={}", id, msg);
            return;
        }

        // Real errors
        log.error("IB ERROR | id={} code={} msg={} reject={}", id, code, msg, advancedReject);

        // Fatal MD errors — break futures so caller fails fast
        boolean isFatalMdError = (code == 200 || code == 354);
        if (isFatalMdError) {
            CompletableFuture<Double> a = askFutures.remove(id);
            if (a != null && !a.isDone()) a.completeExceptionally(new RuntimeException("IB " + code + ": " + msg));
            CompletableFuture<Double> b = bidFutures.remove(id);
            if (b != null && !b.isDone()) b.completeExceptionally(new RuntimeException("IB " + code + ": " + msg));
        }
        events.publishEvent(new IbErrorEvent(id, code, msg));
    }

    @Override public void error(Exception e) { log.error("IB ERROR (Exception)", e); }
    @Override public void error(String str) { log.error("IB ERROR (String) | {}", str); }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        if (price <= 0) return;
        // Live ticks:    1=BID  2=ASK
        // Delayed ticks: 66=DELAYED_BID  67=DELAYED_ASK
        if (field == 1 || field == 66) {
            CompletableFuture<Double> f = bidFutures.remove(tickerId);
            if (f != null && !f.isDone()) {
                log.debug("BID tick | tickerId={} field={} price={}", tickerId, field, price);
                f.complete(price);
            }
        } else if (field == 2 || field == 67) {
            CompletableFuture<Double> f = askFutures.remove(tickerId);
            if (f != null && !f.isDone()) {
                log.debug("ASK tick | tickerId={} field={} price={}", tickerId, field, price);
                f.complete(price);
            }
        }
    }

    @Override
    public void contractDetails(int reqId, ContractDetails details) {
        CompletableFuture<ContractDetails> f = contractDetailsFutures.get(reqId);
        if (f != null && !f.isDone()) f.complete(details);
    }

    @Override
    public void contractDetailsEnd(int reqId) {
        CompletableFuture<Void> end = contractDetailsEndFutures.get(reqId);
        if (end != null && !end.isDone()) end.complete(null);
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
                            double avgFillPrice, long permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {
        log.info("ORDER STATUS | id={} status={} filled={} remaining={} avg={}",
                orderId, status, filled, remaining, avgFillPrice);
        events.publishEvent(new OrderStatusEvent(
                orderId, status,
                filled.value().doubleValue(),
                remaining.value().doubleValue(),
                avgFillPrice, lastFillPrice, whyHeld
        ));
    }

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        log.info("EXEC | orderId={} execId={} price={} qty={}",
                execution.orderId(), execution.execId(), execution.price(), execution.shares());
        events.publishEvent(new ExecutionEvent(
                execution.orderId(),
                execution.execId(),
                execution.price(),
                execution.shares().value().doubleValue(),
                contract.conid()
        ));
    }

    @Override public void managedAccounts(String accountsList) {
        log.info("MANAGED ACCOUNTS={}", accountsList);
    }
    @Override public void connectAck() { log.info("IB CONNECT ACK"); }

    @Override
    public void marketDataType(int reqId, int type) {
        String label = switch (type) {
            case 1 -> "LIVE";
            case 2 -> "FROZEN";
            case 3 -> "DELAYED";
            case 4 -> "DELAYED-FROZEN";
            default -> "UNKNOWN(" + type + ")";
        };
        log.info("MARKET DATA TYPE | reqId={} type={}", reqId, label);
    }

    public record ConnectionClosedEvent() {}
    public record IbErrorEvent(int id, int code, String message) {}

    // ============ Stubs (required by EWrapper) ============
    @Override public void tickSize(int var1, int var2, Decimal var3) {}
    @Override public void tickOptionComputation(int var1, int var2, int var3, double var4, double var6, double var8, double var10, double var12, double var14, double var16, double var18) {}
    @Override public void tickGeneric(int var1, int var2, double var3) {}
    @Override public void tickString(int var1, int var2, String var3) {}
    @Override public void tickEFP(int var1, int var2, double var3, String var5, double var6, int var8, String var9, double var10, double var12) {}
    @Override public void openOrder(int var1, Contract var2, Order var3, OrderState var4) {}
    @Override public void openOrderEnd() {}
    @Override public void updateAccountValue(String var1, String var2, String var3, String var4) {}
    @Override public void updatePortfolio(Contract var1, Decimal var2, double var3, double var5, double var7, double var9, double var11, String var13) {}
    @Override public void updateAccountTime(String var1) {}
    @Override public void accountDownloadEnd(String var1) {}
    @Override public void bondContractDetails(int var1, ContractDetails var2) {}
    @Override public void execDetailsEnd(int var1) {}
    @Override public void updateMktDepth(int var1, int var2, int var3, int var4, double var5, Decimal var7) {}
    @Override public void updateMktDepthL2(int var1, int var2, String var3, int var4, int var5, double var6, Decimal var8, boolean var9) {}
    @Override public void updateNewsBulletin(int var1, int var2, String var3, String var4) {}
    @Override public void receiveFA(int var1, String var2) {}
    @Override public void historicalData(int var1, Bar var2) {}
    @Override public void scannerParameters(String var1) {}
    @Override public void scannerData(int var1, int var2, ContractDetails var3, String var4, String var5, String var6, String var7) {}
    @Override public void scannerDataEnd(int var1) {}
    @Override public void realtimeBar(int var1, long var2, double var4, double var6, double var8, double var10, Decimal var12, Decimal var13, int var14) {}
    @Override public void currentTime(long var1) {}
    @Override public void fundamentalData(int var1, String var2) {}
    @Override public void deltaNeutralValidation(int var1, DeltaNeutralContract var2) {}
    @Override public void tickSnapshotEnd(int var1) {}
    @Override public void commissionAndFeesReport(CommissionAndFeesReport var1) {}
    @Override public void position(String var1, Contract var2, Decimal var3, double var4) {}
    @Override public void positionEnd() {}
    @Override public void accountSummary(int var1, String var2, String var3, String var4, String var5) {}
    @Override public void accountSummaryEnd(int var1) {}
    @Override public void verifyMessageAPI(String var1) {}
    @Override public void verifyCompleted(boolean var1, String var2) {}
    @Override public void verifyAndAuthMessageAPI(String var1, String var2) {}
    @Override public void verifyAndAuthCompleted(boolean var1, String var2) {}
    @Override public void displayGroupList(int var1, String var2) {}
    @Override public void displayGroupUpdated(int var1, String var2) {}
    @Override public void positionMulti(int var1, String var2, String var3, Contract var4, Decimal var5, double var6) {}
    @Override public void positionMultiEnd(int var1) {}
    @Override public void accountUpdateMulti(int var1, String var2, String var3, String var4, String var5, String var6) {}
    @Override public void accountUpdateMultiEnd(int var1) {}
    @Override public void securityDefinitionOptionalParameter(int var1, String var2, int var3, String var4, String var5, Set<String> var6, Set<Double> var7) {}
    @Override public void securityDefinitionOptionalParameterEnd(int var1) {}
    @Override public void softDollarTiers(int var1, SoftDollarTier[] var2) {}
    @Override public void familyCodes(FamilyCode[] var1) {}
    @Override public void symbolSamples(int var1, ContractDescription[] var2) {}
    @Override public void historicalDataEnd(int var1, String var2, String var3) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] var1) {}
    @Override public void tickNews(int var1, long var2, String var4, String var5, String var6, String var7) {}
    @Override public void smartComponents(int var1, Map<Integer, Map.Entry<String, Character>> var2) {}
    @Override public void tickReqParams(int var1, double var2, String var4, int var5) {}
    @Override public void newsProviders(NewsProvider[] var1) {}
    @Override public void newsArticle(int var1, int var2, String var3) {}
    @Override public void historicalNews(int var1, String var2, String var3, String var4, String var5) {}
    @Override public void historicalNewsEnd(int var1, boolean var2) {}
    @Override public void headTimestamp(int var1, String var2) {}
    @Override public void histogramData(int var1, List<HistogramEntry> var2) {}
    @Override public void historicalDataUpdate(int var1, Bar var2) {}
    @Override public void rerouteMktDataReq(int var1, int var2, String var3) {}
    @Override public void rerouteMktDepthReq(int var1, int var2, String var3) {}
    @Override public void marketRule(int var1, PriceIncrement[] var2) {}
    @Override public void pnl(int var1, double var2, double var4, double var6) {}
    @Override public void pnlSingle(int var1, Decimal var2, double var3, double var5, double var7, double var9) {}
    @Override public void historicalTicks(int var1, List<HistoricalTick> var2, boolean var3) {}
    @Override public void historicalTicksBidAsk(int var1, List<HistoricalTickBidAsk> var2, boolean var3) {}
    @Override public void historicalTicksLast(int var1, List<HistoricalTickLast> var2, boolean var3) {}
    @Override public void tickByTickAllLast(int var1, int var2, long var3, double var5, Decimal var7, TickAttribLast var8, String var9, String var10) {}
    @Override public void tickByTickBidAsk(int var1, long var2, double var4, double var6, Decimal var8, Decimal var9, TickAttribBidAsk var10) {}
    @Override public void tickByTickMidPoint(int var1, long var2, double var4) {}
    @Override public void orderBound(long var1, int var3, int var4) {}
    @Override public void completedOrder(Contract var1, Order var2, OrderState var3) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int var1, String var2) {}
    @Override public void wshMetaData(int var1, String var2) {}
    @Override public void wshEventData(int var1, String var2) {}
    @Override public void historicalSchedule(int var1, String var2, String var3, String var4, List<HistoricalSession> var5) {}
    @Override public void userInfo(int var1, String var2) {}
    @Override public void currentTimeInMillis(long var1) {}
    @Override public void orderStatusProtoBuf(OrderStatusProto.OrderStatus var1) {}
    @Override public void openOrderProtoBuf(OpenOrderProto.OpenOrder var1) {}
    @Override public void openOrdersEndProtoBuf(OpenOrdersEndProto.OpenOrdersEnd var1) {}
    @Override public void errorProtoBuf(ErrorMessageProto.ErrorMessage var1) {}
    @Override public void execDetailsProtoBuf(ExecutionDetailsProto.ExecutionDetails var1) {}
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEndProto.ExecutionDetailsEnd var1) {}
}
