package com.mod98.alpaca.spx.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "alpaca")
@Getter
@Setter
public class AlpacaProperties {

    // From .env ALPACA_KEY
    private String key;

    // From .env ALPACA_SECRET
    private String secret;

    // Usually
    private String baseUrl = "https://paper-api.alpaca.markets";

    // Underlying symbol
    private String underlyingSymbol = "SPX";
}
