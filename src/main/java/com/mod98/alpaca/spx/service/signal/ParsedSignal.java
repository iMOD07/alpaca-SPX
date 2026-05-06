// src/main/java/com/mod98/alpaca/spx/service/signal/ParsedSignal.java
package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ParsedSignal {

    // PREPARE / ENTRY / PRICE_ALERT / CANCEL / UPDATE / ...
    private SignalType signalType;

    // SPXW
    private String symbol;
    // CALL / PUT
    private String optionType;
    private BigDecimal strike;
    private LocalDate expiryDate;
    // Preparation (1 + 2)
    private BigDecimal preparePrice;
    // From "Entry"
    private BigDecimal entrySignalPrice;
    // From "Update Command X.Y"
    private BigDecimal updatePrice;
    // From the good news
    private BigDecimal priceAlert;
    private Long telegramMessageId;
    // If it was a reply
    private Long replyToMessageId;
    private String rawText;

    // Optional: raw SL/TP إن عناد ذكرها في النص – لا تغير منطق TP/SL الذهبي
    private BigDecimal stopLossFromSignal;

    private BigDecimal takeProfitFromSignal;

}
