package com.mod98.alpaca.spx.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Order;
import com.ib.client.OrderCancel;
import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.Deal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * IBKR Execution — v3.2.
 *
 * Changes from v3.1:
 *  ✅ SL is STP-LMT instead of STP (slippage protection)
 *  ✅ placeBracket retries up to 3 times with 2s delay
 *  ✅ buildContractFromConId now sets tradingClass="SPXW"
 *
 * SL-LMT math:
 *   STP trigger = slPrice (e.g. $2.90 = entry-$1)
 *   LMT floor   = slPrice - slLmtOffset (e.g. $2.40 = $2.90 - $0.50)
 *   → IBKR will sell as long as fill >= $2.40
 *   → If gap-down below $2.40 → order stays pending (manual intervention)
 *
 * Operational note on STP-LMT pending:
 *   In a violent gap-down, SL may NOT execute. We accept this trade-off
 *   because the alternative (pure STP) caused -40% slippage in volatile markets.
 *   Mitigation: log + position remains visible in TWS for manual close.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrExecutionService {

    private static final BigDecimal MIN_OPTION_PRICE = new BigDecimal("0.05");

    private final IbkrConnectionManager conn;
    private final IbkrOrderIdService orderIds;
    private final IbkrMarketDataService marketData;
    private final TradingProperties trading;

    // =========================================================
    // ENTRY
    // =========================================================
    public EntryDecisionResult placeEntryWithReservedId(Deal deal, Contract contract, int reservedOrderId) {
        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }

        BigDecimal signalPrice = deal.getEntrySignalPrice();
        BigDecimal lower = signalPrice.subtract(trading.getEntryMinOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = signalPrice.add(trading.getEntryMaxOffset()).setScale(2, RoundingMode.HALF_UP);

        // Snapshot ASK + BID
        long mdTimeoutMs = trading.getMarketDataTimeout().toMillis();
        double askD = marketData.getAskSnapshot(contract, (int) mdTimeoutMs);
        double bidD = marketData.getBidSnapshot(contract, (int) mdTimeoutMs);

        BigDecimal ask = BigDecimal.valueOf(askD).setScale(2, RoundingMode.HALF_UP);
        BigDecimal bid = BigDecimal.valueOf(bidD).setScale(2, RoundingMode.HALF_UP);
        BigDecimal spread = ask.subtract(bid).abs();

        // Guard 1: spread
        if (spread.compareTo(trading.getMaxSpread()) > 0) {
            log.warn("ENTRY BLOCKED | spread too wide | spread={} max={} bid={} ask={}",
                    spread, trading.getMaxSpread(), bid, ask);
            return EntryDecisionResult.blocked("SPREAD_TOO_WIDE", lower, upper, ask, bid, contract.conid());
        }

        // Guard 2: range
        if (ask.compareTo(lower) < 0 || ask.compareTo(upper) > 0) {
            log.warn("ENTRY BLOCKED | ask out of range | ask={} range=[{}..{}]", ask, lower, upper);
            return EntryDecisionResult.blocked("OUT_OF_RANGE", lower, upper, ask, bid, contract.conid());
        }

        // Guard 3: slippage
        BigDecimal slippage = ask.subtract(signalPrice).abs();
        if (slippage.compareTo(trading.getMaxSlippage()) > 0) {
            log.warn("ENTRY BLOCKED | slippage too high | signal={} ask={} slip={} max={}",
                    signalPrice, ask, slippage, trading.getMaxSlippage());
            return EntryDecisionResult.blocked("SLIPPAGE_TOO_HIGH", lower, upper, ask, bid, contract.conid());
        }

        // Place BUY LMT @ upper (marketable)
        Order o = new Order();
        o.action("BUY");
        o.orderType("LMT");
        o.totalQuantity(Decimal.get(trading.getOptionQty()));
        o.lmtPrice(upper.doubleValue());
        o.tif("DAY");
        o.transmit(true);
        o.outsideRth(false);

        log.info("PLACING ENTRY | dealId={} orderId={} conId={} ask={} bid={} spread={} limit={}",
                deal.getId(), reservedOrderId, contract.conid(), ask, bid, spread, upper);

        conn.getClient().placeOrder(reservedOrderId, contract, o);

        return EntryDecisionResult.placed(reservedOrderId, contract.conid(), lower, upper, ask, bid);
    }

    // =========================================================
    // BRACKET (TP + SL via OCA) — with 3-retry safety net
    // =========================================================
    public BracketResult placeBracket(Deal deal) {
        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }
        if (deal.getIbkrContractId() == null) {
            throw new IllegalStateException("conId missing on deal " + deal.getId());
        }

        // Retry up to 3 times with 2s delay (network glitch resilience)
        Exception lastException = null;
        int maxRetries = trading.getBracketRetryMax();
        long retryDelayMs = trading.getBracketRetryDelay().toMillis();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return doPlaceBracket(deal, attempt);
            } catch (Exception e) {
                lastException = e;
                log.warn("Bracket attempt {}/{} failed for dealId={} | error={}",
                        attempt, maxRetries, deal.getId(), e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Bracket retry interrupted", ie);
                    }
                }
            }
        }

        // All retries exhausted
        throw new RuntimeException(
                "Bracket placement failed after " + maxRetries + " attempts for dealId=" + deal.getId(),
                lastException);
    }

    private BracketResult doPlaceBracket(Deal deal, int attempt) {
        Contract contract = buildContractFromConId(deal.getIbkrContractId());
        int tpId = orderIds.nextOrderId();
        int slId = orderIds.nextOrderId();
        String oca = "deal-" + deal.getId();

        // TP: SELL LMT @ tpPrice
        Order tp = new Order();
        tp.action("SELL");
        tp.orderType("LMT");
        tp.totalQuantity(Decimal.get(trading.getOptionQty()));
        tp.lmtPrice(deal.getTpPrice().doubleValue());
        tp.tif("GTC");
        tp.ocaGroup(oca);
        tp.ocaType(1);              // CANCEL_WITH_BLOCK
        tp.transmit(false);

        // ⭐ NEW v3.2: SL is STP-LMT (not STP)
        // Trigger at slPrice, LMT floor at slPrice - slLmtOffset
        BigDecimal slTrigger = deal.getSlPrice();
        BigDecimal slLmtFloor = slTrigger.subtract(trading.getSlLmtOffset())
                .setScale(2, RoundingMode.HALF_UP)
                .max(MIN_OPTION_PRICE);

        Order sl = new Order();
        sl.action("SELL");
        sl.orderType("STP LMT");                    // ← changed from "STP"
        sl.totalQuantity(Decimal.get(trading.getOptionQty()));
        sl.auxPrice(slTrigger.doubleValue());       // trigger price
        sl.lmtPrice(slLmtFloor.doubleValue());      // ← NEW: lmt floor
        sl.tif("GTC");
        sl.ocaGroup(oca);
        sl.ocaType(1);
        sl.transmit(true);

        log.info("PLACING BRACKET (attempt {}) | dealId={} tpId={}@{} slId={} STP={} LMT={}",
                attempt, deal.getId(),
                tpId, deal.getTpPrice(),
                slId, slTrigger, slLmtFloor);

        conn.getClient().placeOrder(tpId, contract, tp);
        conn.getClient().placeOrder(slId, contract, sl);

        return new BracketResult(tpId, slId);
    }

    // =========================================================
    // CANCEL
    // =========================================================
    public void cancelOrder(int orderId) {
        if (!conn.isConnected()) {
            log.warn("Cannot cancel orderId={} — IBKR disconnected", orderId);
            return;
        }
        log.info("CANCELLING orderId={}", orderId);
        conn.getClient().cancelOrder(orderId, new OrderCancel());
    }

    // =========================================================
    // Helpers
    // =========================================================
    private Contract buildContractFromConId(int conId) {
        Contract c = new Contract();
        c.conid(conId);
        c.exchange("SMART");
        c.currency("USD");
        c.tradingClass("SPXW");     // ⭐ explicit (was missing in v3.1)
        return c;
    }

    // =========================================================
    // DTOs
    // =========================================================
    public record EntryDecisionResult(
            boolean placed,
            String blockReason,
            Integer orderId,
            Integer conId,
            BigDecimal lower,
            BigDecimal upper,
            BigDecimal ask,
            BigDecimal bid
    ) {
        public static EntryDecisionResult blocked(String reason, BigDecimal lower, BigDecimal upper,
                                                  BigDecimal ask, BigDecimal bid, int conId) {
            return new EntryDecisionResult(false, reason, null, conId, lower, upper, ask, bid);
        }
        public static EntryDecisionResult placed(int orderId, int conId, BigDecimal lower,
                                                 BigDecimal upper, BigDecimal ask, BigDecimal bid) {
            return new EntryDecisionResult(true, null, orderId, conId, lower, upper, ask, bid);
        }
    }

    public record BracketResult(int tpOrderId, int slOrderId) {}
}
