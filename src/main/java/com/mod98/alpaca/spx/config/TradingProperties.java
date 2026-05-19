package com.mod98.alpaca.spx.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "spx.trading")
public class TradingProperties {

    @Min(1)
    private int optionQty = 1;

    @DecimalMin("0.0")
    private BigDecimal entryMinOffset = new BigDecimal("0.30");

    @DecimalMin("0.0")
    private BigDecimal entryMaxOffset = new BigDecimal("0.30");

    @DecimalMin("0.0")
    private BigDecimal tpOffset = new BigDecimal("1.00");

    @DecimalMin("0.0")
    private BigDecimal slOffset = new BigDecimal("1.00");

    /** ⭐ NEW v3.2 — STP-LMT floor distance below SL trigger */
    @DecimalMin("0.0")
    private BigDecimal slLmtOffset = new BigDecimal("0.50");

    /** Max spread allowed between BID and ASK. */
    @DecimalMin("0.0")
    private BigDecimal maxSpread = new BigDecimal("0.30");

    /** Max slippage between signal and actual ASK. */
    @DecimalMin("0.0")
    private BigDecimal maxSlippage = new BigDecimal("0.10");

    /** Timeout for entry fill before cancellation. */
    private Duration entryFillTimeout = Duration.ofSeconds(15);

    /** Timeout for market-data snapshot request. */
    private Duration marketDataTimeout = Duration.ofSeconds(3);

    /** Place TP/SL bracket automatically after ENTRY fill. */
    private boolean placeBracketOrders = true;

    /** Kill switch — instant disable without restart. */
    private boolean tradingEnabled = true;

    /** ⭐ NEW v3.1 — stop after N consecutive losses. */
    @Min(1)
    private int maxConsecutiveLosses = 4;

    /** ⭐ NEW v3.2 — max simultaneously open deals. */
    @Min(1)
    private int maxConcurrentDeals = 3;

    /** ⭐ NEW v3.2 — bracket placement retry count. */
    @Min(1)
    private int bracketRetryMax = 3;

    /** ⭐ NEW v3.2 — delay between bracket retry attempts. */
    private Duration bracketRetryDelay = Duration.ofSeconds(2);

    /** ⭐ NEW v3.2 — enable buying power pre-flight check. */
    private boolean buyingPowerCheckEnabled = true;

    /** ⭐ NEW v3.2 — safety buffer added to required cost. */
    @DecimalMin("0.0")
    private BigDecimal buyingPowerBuffer = new BigDecimal("100");
}
