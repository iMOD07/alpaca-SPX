package com.mod98.alpaca.spx.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Order;
import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.domain.Deal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrExecutionService {

    private final IbkrConnectionManager conn;
    private final IbkrOrderIdService orderIds;
    private final IbkrContractService contractService;
    private final IbkrMarketDataService marketData;
    private final TradingProperties trading;

    /**
     * ENTRY حقيقي:
     * - يحسب lower/upper من offsets
     * - يجلب ASK الحالي من IBKR
     * - إذا داخل الرينج: يرسل BUY Limit بسقف upper
     * - إذا خارج: ما يرسل شي
     */
    public EntryDecisionResult placeEntrySpxwCall(LocalDate expiry, double strike, BigDecimal signalPrice) {
        if (!conn.isConnected()) throw new IllegalStateException("IBKR not connected");

        BigDecimal lower = signalPrice.subtract(trading.getEntryMinOffset()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = signalPrice.add(trading.getEntryMaxOffset()).setScale(2, RoundingMode.HALF_UP);

        // 1) Resolve contract (conId)
        Contract contract = contractService.resolveSpxwOptionContract(expiry, strike, "C");

        // 2) Ask snapshot
        double ask = marketData.getAskSnapshot(contract, 2500);
        BigDecimal askBd = BigDecimal.valueOf(ask).setScale(2, RoundingMode.HALF_UP);

        // 3) Range check
        if (askBd.compareTo(lower) < 0 || askBd.compareTo(upper) > 0) {
            log.warn("ENTRY BLOCKED | signal={} range=[{}..{}] ask={}", signalPrice, lower, upper, askBd);
            return EntryDecisionResult.blocked(lower, upper, askBd);
        }

        // 4) Place marketable limit (ceiling = upper)
        int orderId = orderIds.nextOrderId();

        Order o = new Order();
        o.action("BUY");
        o.orderType("LMT");
        o.totalQuantity(Decimal.get(trading.getOptionQty()));
        o.lmtPrice(upper.doubleValue());

        log.info("PLACING ENTRY | orderId={} ask={} range=[{}..{}] limit={}", orderId, askBd, lower, upper, upper);
        conn.getClient().placeOrder(orderId, contract, o);

        return EntryDecisionResult.placed(orderId, lower, upper, askBd);
    }

    public void closeDealPosition(Deal deal, String reason) {

        if (!conn.isConnected()) {
            throw new IllegalStateException("IBKR not connected");
        }

        // 1) نحتاج نفس العقد اللي دخلنا فيه (conId محفوظ)
        Contract contract = new Contract();
        contract.conid(deal.getgetIbkrContractId()); // لازم يكون محفوظ عند ENTRY
        contract.exchange("CBOE");

        // 2) نجيب ASK / BID حسب SELL
        double bid = marketData.getBidSnapshot(contract, 2000);

        // 3) Marketable Limit (SELL)
        BigDecimal limit = BigDecimal.valueOf(bid)
                .setScale(2, RoundingMode.HALF_UP);

        int orderId = orderIds.nextOrderId();

        Order o = new Order();
        o.action("SELL");
        o.orderType("LMT");
        o.totalQuantity(Decimal.get(trading.getOptionQty()));
        o.lmtPrice(limit.doubleValue());

        log.info("CLOSING DEAL | dealId={} reason={} bid={} limit={}",
                deal.getId(), reason, bid, limit);

        conn.getClient().placeOrder(orderId, contract, o);

        // حفظ رقم الأمر
        deal.setIbkrExitOrderId(orderId);
    }


    public record EntryDecisionResult(
            boolean placed,
            Integer orderId,
            BigDecimal lower,
            BigDecimal upper,
            BigDecimal ask
    ) {
        public static EntryDecisionResult blocked(BigDecimal lower, BigDecimal upper, BigDecimal ask) {
            return new EntryDecisionResult(false, null, lower, upper, ask);
        }
        public static EntryDecisionResult placed(int orderId, BigDecimal lower, BigDecimal upper, BigDecimal ask) {
            return new EntryDecisionResult(true, orderId, lower, upper, ask);
        }
    }
}
