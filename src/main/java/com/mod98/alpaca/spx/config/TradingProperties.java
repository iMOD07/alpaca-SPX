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
    private BigDecimal entryMinOffset = new BigDecimal("0.15");

    @DecimalMin("0.0")
    private BigDecimal entryMaxOffset = new BigDecimal("0.30");

    @DecimalMin("0.0")
    private BigDecimal tpOffset = new BigDecimal("1.00");

    @DecimalMin("0.0")
    private BigDecimal slOffset = new BigDecimal("1.00");

    private boolean allowMultipleDeals = true;

    /** أقصى سعر سبريد مسموح (ASK - BID). خارجه ما ندخل. */
    @DecimalMin("0.0")
    private BigDecimal maxSpread = new BigDecimal("0.30");

    /** أقصى slippage مسموح بين سعر الإشارة والسعر الفعلي. */
    @DecimalMin("0.0")
    private BigDecimal maxSlippage = new BigDecimal("0.10");

    /** Timeout لانتظار fill قبل ما نلغي الأمر. */
    private Duration entryFillTimeout = Duration.ofSeconds(15);

    /** Timeout لرسالة "تجهيز" — بعدها ما تنفع للدخول. */
    private Duration prepareValidity = Duration.ofMinutes(30);

    /** Timeout لـ market data snapshot. */
    private Duration marketDataTimeout = Duration.ofSeconds(3);

    /** هل نضع TP/SL bracket تلقائياً بعد ENTRY fill. */
    private boolean placeBracketOrders = true;

    /** kill switch — إيقاف فوري لكل التداول بدون restart. */
    private boolean tradingEnabled = true;
}
