package com.mod98.alpaca.spx.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "spx.trading")
public class TradingProperties {

    // OPTION_QTY
    private int optionQty = 1;

    // ENTRY_MIN_OFFSET = amount below signal price
    private BigDecimal entryMinOffset = new BigDecimal("0.15");
    // ENTRY_MAX_OFFSET = amount above signal price
    private BigDecimal entryMaxOffset = new BigDecimal("0.15");

    // TP_OFFSET
    private BigDecimal tpOffset = new BigDecimal("1.0");

    // SL_OFFSET
    private BigDecimal slOffset = new BigDecimal("1.0");

    // ALLOW_MULTIPLE_DEALS
    private boolean allowMultipleDeals = true;

}