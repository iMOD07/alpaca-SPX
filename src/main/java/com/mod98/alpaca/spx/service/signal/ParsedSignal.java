package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * نتيجة تحليل الرسالة — immutable.
 *
 * Invariants:
 *  - signalType != null
 *  - telegramMessageId != null
 *  - For ENTRY:  optionType, strike, expiryDate, entryPrice REQUIRED
 *  - For UPDATE: replyToMessageId REQUIRED + at least one of (newEntry/newSL/newTP)
 *  - For CANCEL: replyToMessageId REQUIRED
 */
@Value
@Builder
public class ParsedSignal {

    SignalType signalType;
    Long telegramMessageId;
    Long replyToMessageId;
    String rawText;

    // ENTRY data
    String symbol;            // SPXW
    String optionType;        // CALL | PUT
    BigDecimal strike;
    LocalDate expiryDate;
    BigDecimal entryPrice;    // السعر اللحظي للأوبشن من الإشارة

    // UPDATE data (any of these can be set)
    BigDecimal newEntry;
    BigDecimal newStopLoss;
    BigDecimal newTakeProfit;

    public boolean isValidEntry() {
        return signalType == SignalType.ENTRY
                && optionType != null
                && strike != null
                && expiryDate != null
                && entryPrice != null
                && entryPrice.signum() > 0;
    }

    public boolean isValidReply() {
        return (signalType == SignalType.UPDATE || signalType == SignalType.CANCEL)
                && replyToMessageId != null;
    }

    public boolean hasUpdateData() {
        return newEntry != null || newStopLoss != null || newTakeProfit != null;
    }
}
