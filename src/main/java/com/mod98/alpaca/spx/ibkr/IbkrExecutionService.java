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
 * IBKR Execution — يحتوي فقط على عمليات الإرسال للأوامر.
 *
 * مسؤوليات الكلاس:
 *  - placeEntryWithReservedId: BUY LMT (تستقبل orderId و contract محجوزَين مسبقاً من EntrySignalHandler).
 *  - placeBracket: TP + SL كـ OCA group بعد ENTRY filled.
 *  - closeDealPosition: SELL marketable LMT للخروج اليدوي.
 *  - cancelOrder: إلغاء أمر معلّق.
 *
 * ملاحظة:
 *  - resolveContract يحدث في EntrySignalHandler.preFlight (لذا لا نحتاج IbkrContractService هنا).
 *  - status updates تتم في OrderTrackingService بناءً على callbacks من IBKR (لا تتم هنا).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrExecutionService {

    private final IbkrConnectionManager conn;
    private final IbkrOrderIdService orderIds;
    private final IbkrMarketDataService marketData;
    private final TradingProperties trading;

    // =========================================================
    // ENTRY — orderId و contract محجوزان مسبقاً (لمنع race مع OrderTrackingService)
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

        // 1) Spread guard
        if (spread.compareTo(trading.getMaxSpread()) > 0) {
            log.warn("ENTRY BLOCKED | spread too wide | spread={} max={} bid={} ask={}",
                    spread, trading.getMaxSpread(), bid, ask);
            return EntryDecisionResult.blocked("SPREAD_TOO_WIDE", lower, upper, ask, bid, contract.conid());
        }

        // 2) Range guard
        if (ask.compareTo(lower) < 0 || ask.compareTo(upper) > 0) {
            log.warn("ENTRY BLOCKED | ask out of range | ask={} range=[{}..{}]", ask, lower, upper);
            return EntryDecisionResult.blocked("OUT_OF_RANGE", lower, upper, ask, bid, contract.conid());
        }

        // 3) Slippage guard
        BigDecimal slippage = ask.subtract(signalPrice).abs();
        if (slippage.compareTo(trading.getMaxSlippage()) > 0) {
            log.warn("ENTRY BLOCKED | slippage too high | signal={} ask={} slip={} max={}",
                    signalPrice, ask, slippage, trading.getMaxSlippage());
            return EntryDecisionResult.blocked("SLIPPAGE_TOO_HIGH", lower, upper, ask, bid, contract.conid());
        }

        // 4) Place BUY LMT @ upper (marketable)
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
    // BRACKET (TP + SL via OCA) — يُستدعى من OrderTrackingService بعد ENTRY filled
    // =========================================================
    public BracketResult placeBracket(Deal deal) {
        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }
        if (deal.getIbkrContractId() == null) {
            throw new IllegalStateException("conId missing on deal " + deal.getId());
        }

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
        tp.ocaType(1);          // CANCEL_WITH_BLOCK
        tp.transmit(false);

        // SL: SELL STP @ slPrice
        Order sl = new Order();
        sl.action("SELL");
        sl.orderType("STP");
        sl.totalQuantity(Decimal.get(trading.getOptionQty()));
        sl.auxPrice(deal.getSlPrice().doubleValue());
        sl.tif("GTC");
        sl.ocaGroup(oca);
        sl.ocaType(1);
        sl.transmit(true);      // آخر واحد يفعّل المجموعة كاملة

        log.info("PLACING BRACKET | dealId={} tpId={}@{} slId={}@{}",
                deal.getId(), tpId, deal.getTpPrice(), slId, deal.getSlPrice());

        conn.getClient().placeOrder(tpId, contract, tp);
        conn.getClient().placeOrder(slId, contract, sl);

        return new BracketResult(tpId, slId);
    }

    // =========================================================
    // MANUAL EXIT — يُستدعى من ManualExitHandler
    // =========================================================
    public int closeDealPosition(Deal deal, String reason) {
        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }
        if (deal.getIbkrContractId() == null) {
            throw new IllegalStateException("conId missing on deal " + deal.getId());
        }

        Contract contract = buildContractFromConId(deal.getIbkrContractId());

        long mdTimeoutMs = trading.getMarketDataTimeout().toMillis();
        double bidD = marketData.getBidSnapshot(contract, (int) mdTimeoutMs);

        // SPX options tick: 0.05 لو price < 3.00، 0.10 لو ≥ 3.00
        BigDecimal bid = BigDecimal.valueOf(bidD).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tick = bid.compareTo(new BigDecimal("3.00")) >= 0
                ? new BigDecimal("0.10") : new BigDecimal("0.05");
        BigDecimal limit = bid.subtract(tick).max(new BigDecimal("0.05"));

        int orderId = orderIds.nextOrderId();
        Order o = new Order();
        o.action("SELL");
        o.orderType("LMT");
        o.totalQuantity(Decimal.get(trading.getOptionQty()));
        o.lmtPrice(limit.doubleValue());
        o.tif("DAY");
        o.transmit(true);

        log.info("CLOSING DEAL | dealId={} reason={} bid={} limit={} orderId={}",
                deal.getId(), reason, bid, limit, orderId);

        conn.getClient().placeOrder(orderId, contract, o);
        return orderId;
    }

    // =========================================================
    // CANCEL — يُستدعى من OrderTrackingService عند timeout
    // =========================================================
    public void cancelOrder(int orderId) {
        if (!conn.isConnected()) {
            log.warn("Cannot cancel orderId={} — IBKR disconnected", orderId);
            return;
        }
        log.info("CANCELLING orderId={}", orderId);
        // IBKR API 10.x signature
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
