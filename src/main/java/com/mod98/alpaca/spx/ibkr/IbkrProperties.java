package com.mod98.alpaca.spx.ibkr;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "ibkr")
public class IbkrProperties {
    private String host = "127.0.0.1";
    private int port = 7497;
    private int clientId = 7;
    private String account = "DU0960320";
}
