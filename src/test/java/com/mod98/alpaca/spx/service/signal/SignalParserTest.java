package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies extraction against the PDF Type 2 (entry) and Type 4 (reply) examples.
 *
 * Authority model under test:
 *   - caption  → decision + option type (CALL/PUT)
 *   - OCR card → strike + expiry + live price (price fallback when not inline)
 */
class SignalParserTest {

    private final SignalParser parser = new SignalParser();

    /** OCR header line uses a future expiry so the past-expiry safety guard doesn't fire. */
    private static String ocrCard(int year) {
        return "SPXW $6,845 28 Nov " + year + " (W) Call 100\n"
                + "3.30 +0.80 +32.00%\n"
                + "Open 4.20 Mid 3.25\n"
                + "High 6.00 Low 2.01";
    }

    @Test
    @DisplayName("PDF Type2: 'دخول CALL' + OCR card → CALL/6845/28-Nov/3.30 extracted")
    void parseEntry_pdfType2_extractsAllFields() {
        int futureYear = LocalDate.now().getYear() + 1;
        String caption = "🟢 دخول CALL وقف الخسارة عند مستوى : 6763 الهدف المتوقع مستوى : 6827";

        ParsedSignal s = parser.parseEntry(caption, ocrCard(futureYear), 1001L);

        assertThat(s).isNotNull();
        assertThat(s.getSignalType()).isEqualTo(SignalType.ENTRY);
        assertThat(s.getOptionType()).isEqualTo("CALL");
        assertThat(s.getStrike()).isEqualByComparingTo("6845");
        assertThat(s.getExpiryDate()).isEqualTo(LocalDate.of(futureYear, 11, 28));
        assertThat(s.getEntryPrice()).isEqualByComparingTo("3.30");
        assertThat(s.isValidEntry()).isTrue();
    }

    @Test
    @DisplayName("Safety: PDF's literal past expiry (28 Nov 25) → null (no stale-contract trade)")
    void parseEntry_pastExpiry_returnsNull() {
        String caption = "دخول CALL";
        // PDF's real card year is 25 → 2025, which is in the past today.
        assertThat(parser.parseEntry(caption, ocrCard(25), 1002L)).isNull();
    }

    @Test
    @DisplayName("Type from OCR fallback: 'دخول' (no CALL/PUT in text) + 'Call' card → CALL")
    void parseEntry_optionTypeFromOcrFallback() {
        int futureYear = LocalDate.now().getYear() + 1;
        ParsedSignal s = parser.parseEntry("دخول الان", ocrCard(futureYear), 1003L);
        assertThat(s).isNotNull();
        assertThat(s.getOptionType()).isEqualTo("CALL"); // recovered from card "(W) Call 100"
    }

    @Test
    @DisplayName("Safety: 'دخول' but no CALL/PUT in text AND none in OCR → null")
    void parseEntry_noOptionTypeAnywhere_returnsNull() {
        assertThat(parser.parseEntry("دخول الان", "random noise no contract", 1003L)).isNull();
    }

    @Test
    @DisplayName("Safety: incomplete data (no OCR strike/expiry) → null")
    void parseEntry_incomplete_returnsNull() {
        assertThat(parser.parseEntry("دخول CALL", "no card here just noise", 1004L)).isNull();
    }

    @Test
    @DisplayName("PDF Type4: reply 'الغاء الأمر' → CANCEL")
    void parseReply_cancel() {
        ParsedSignal s = parser.parseReply("الغاء الأمر", 2001L, 1001L);
        assertThat(s).isNotNull();
        assertThat(s.getSignalType()).isEqualTo(SignalType.CANCEL);
        assertThat(s.getReplyToMessageId()).isEqualTo(1001L);
        assertThat(s.isValidReply()).isTrue();
    }

    @Test
    @DisplayName("PDF Type4: reply 'تعديل' with SL/TP → UPDATE with new values")
    void parseReply_update() {
        ParsedSignal s = parser.parseReply("تعديل وقف الخسارة 2.50 الهدف 4.50", 2002L, 1001L);
        assertThat(s).isNotNull();
        assertThat(s.getSignalType()).isEqualTo(SignalType.UPDATE);
        assertThat(s.getNewStopLoss()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(s.getNewTakeProfit()).isEqualByComparingTo(new BigDecimal("4.50"));
        assertThat(s.hasUpdateData()).isTrue();
    }

    @Test
    @DisplayName("Reply with no replyTo → null (cannot target a deal)")
    void parseReply_noReplyTo_returnsNull() {
        assertThat(parser.parseReply("الغاء", 2003L, null)).isNull();
    }
}
