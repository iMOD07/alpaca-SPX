package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.config.TradingProperties;
import com.mod98.alpaca.spx.ibkr.IbkrAccountSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Buying Power check — v3.2.
 *
 * Before placing BUY, validates account has enough funds.
 *
 * Cost calculation:
 *   cost = signalPrice * 100 (multiplier) * optionQty + safetyBuffer
 *   e.g. signal=3.9, qty=1 → cost = 390 + buffer (default 100) = $490
 *
 * If buyingPower < cost → reject entry.
 *
 * Buffer covers:
 *   - Slippage during entry
 *   - Commission (~$1/contract)
 *   - Margin requirement variability
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyingPowerService {

    private static final int MD_TIMEOUT_MS = 5000;
    private static final BigDecimal MULTIPLIER = new BigDecimal("100");

    @Value("${spx.trading.buying-power-buffer:100}")
    private BigDecimal safetyBuffer;

    @Value("${spx.trading.buying-power-check-enabled:true}")
    private boolean enabled;

    private final IbkrAccountSummaryService accountSummary;
    private final TradingProperties trading;

    public enum Result {
        OK,
        INSUFFICIENT_FUNDS,
        UNAVAILABLE   // can't fetch (treat as fail-safe block)
    }

    /**
     * @param signalPrice entry signal price (BigDecimal)
     * @return Result code + details in log
     */
    public Result check(BigDecimal signalPrice) {
        if (!enabled) {
            return Result.OK;
        }

        if (signalPrice == null || signalPrice.signum() <= 0) {
            log.warn("BuyingPower: invalid signalPrice={}", signalPrice);
            return Result.UNAVAILABLE;
        }

        // Required cost
        BigDecimal qty = new BigDecimal(trading.getOptionQty());
        BigDecimal cost = signalPrice.multiply(MULTIPLIER).multiply(qty);
        BigDecimal required = cost.add(safetyBuffer);

        // Get current buying power
        BigDecimal bp = accountSummary.getBuyingPower(MD_TIMEOUT_MS);
        if (bp == null) {
            // ⚠️ Fail-safe: if we can't determine buying power, BLOCK the entry
            // (we'd rather miss a trade than over-leverage)
            log.error("🛑 BuyingPower UNAVAILABLE → blocking entry (fail-safe)");
            return Result.UNAVAILABLE;
        }

        if (bp.compareTo(required) < 0) {
            log.error("🛑 BuyingPower INSUFFICIENT | required=${} available=${} cost=${} buffer=${}",
                    required, bp, cost, safetyBuffer);
            return Result.INSUFFICIENT_FUNDS;
        }

        log.info("✅ BuyingPower OK | required=${} available=${}", required, bp);
        return Result.OK;
    }
}
