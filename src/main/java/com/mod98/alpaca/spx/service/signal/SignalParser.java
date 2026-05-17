package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser موحّد للإشارات — مطبّق المتطلبات v2 بدقة.
 *
 * Authority Rules (مهم جداً):
 *   - النص (caption) = AUTHORITATIVE لـ:
 *       * قرار الدخول (ENTRY decision)
 *       * نوع العقد (CALL/PUT)
 *       * السعر إن وُجد inline
 *   - الصورة (OCR) = AUTHORITATIVE لـ:
 *       * Strike
 *       * Expiry date
 *       * Live price (لو ما فيه inline price في النص)
 *
 * المنطق: المرسل يكتب "دخول CALL" → النص يحدد type.
 * الصورة فيها strike و expiry بصيغة محددة (card header).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalParser {

    // =========================================================
    // KEYWORDS
    // =========================================================

    /** ENTRY trigger — فقط للتأكد بعد ما الـ Gate سمح بالعبور. */
    private static final Pattern ENTRY_TRIGGER = Pattern.compile(
            "\\b(دخول|ادخل|🟢\\s*دخول)\\b", Pattern.CASE_INSENSITIVE);

    /** Type from text (authoritative). */
    private static final Pattern TYPE_FROM_TEXT = Pattern.compile(
            "\\b(call|put|كول|بوت)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANCEL_KEYWORDS = Pattern.compile(
            "\\b(الغ(?:اء|ي|)|إلغاء|ignore|cancel|skip|لا\\s*تدخل|اخرج|خروج|بيع|exit|close)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_KEYWORDS = Pattern.compile(
            "\\b(تعديل|تحديث|update|modify|change)\\b", Pattern.CASE_INSENSITIVE);

    // =========================================================
    // FIELD EXTRACTORS
    // =========================================================

    /** SPX option card header (from OCR): "SPXW $6,810  28 Nov 25 (W) Call 100" */
    private static final Pattern CONTRACT_HEADER = Pattern.compile(
            "(SPXW?)\\s*\\$?(\\d[\\d,]*)\\s*(\\d{1,2})\\s*([A-Za-z]{3,9})\\s*(\\d{2,4})\\s*\\(?W?\\)?\\s*(Call|Put)",
            Pattern.CASE_INSENSITIVE);

    /** Live price in OCR card: "3.30 +0.80" or "3.30 -0.50" */
    private static final Pattern LIVE_PRICE_OCR = Pattern.compile(
            "(?m)^\\s*(\\d+\\.\\d{1,2})\\s+[+\\-]\\d");

    /** Inline price in text: "@ 3.30" or "بسعر 3.30" */
    private static final Pattern INLINE_PRICE = Pattern.compile(
            "(?:بسعر|سعر|@|price|at)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Strike (from OCR or text): "strike 6810" or "استرايك 6810" */
    private static final Pattern STRIKE = Pattern.compile(
            "(?:strike|استرايك)\\s*[:：]?\\s*(\\d{3,5}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    // Update value patterns (used in parseReply)
    private static final Pattern SL_UPDATE = Pattern.compile(
            "\\b(?:sl|stop|stoploss|وقف(?:\\s*الخسارة)?)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TP_UPDATE = Pattern.compile(
            "\\b(?:tp|target|takeprofit|الهدف|هدف)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTRY_UPDATE = Pattern.compile(
            "\\b(?:entry|سعر\\s*الدخول|تحديث\\s*(?:الأمر|الامر|السعر)?)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Validation bounds للـ SPX strike. */
    private static final BigDecimal STRIKE_MIN = new BigDecimal("1000");
    private static final BigDecimal STRIKE_MAX = new BigDecimal("10000");

    /** Validation bounds للسعر — option price لا يكون > 500 لـ SPXW عادة. */
    private static final BigDecimal PRICE_MIN = new BigDecimal("0.05");
    private static final BigDecimal PRICE_MAX = new BigDecimal("500.00");

    // =========================================================
    // MONTH NAMES
    // =========================================================
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1),  Map.entry("january", 1),  Map.entry("يناير", 1),
            Map.entry("feb", 2),  Map.entry("february", 2), Map.entry("فبراير", 2),
            Map.entry("mar", 3),  Map.entry("march", 3),    Map.entry("مارس", 3),
            Map.entry("apr", 4),  Map.entry("april", 4),    Map.entry("ابريل", 4),
            Map.entry("may", 5),  Map.entry("مايو", 5),
            Map.entry("jun", 6),  Map.entry("june", 6),     Map.entry("يونيو", 6),
            Map.entry("jul", 7),  Map.entry("july", 7),     Map.entry("يوليو", 7),
            Map.entry("aug", 8),  Map.entry("august", 8),   Map.entry("اغسطس", 8),
            Map.entry("sep", 9),  Map.entry("september", 9),Map.entry("سبتمبر", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("اكتوبر", 10),
            Map.entry("nov", 11), Map.entry("november", 11),Map.entry("نوفمبر", 11),
            Map.entry("dec", 12), Map.entry("december", 12),Map.entry("ديسمبر", 12)
    );

    // =========================================================
    // PUBLIC API
    // =========================================================

    /**
     * Parse ENTRY signal بعد ما الـ Gate تأكد إن فيه "دخول".
     *
     * Authority:
     *   - النص = type (CALL/PUT) + decision
     *   - OCR  = strike + expiry + (price fallback)
     *
     * @return ParsedSignal لو البيانات كاملة، null لو ناقصة (→ SKIP unclear)
     */
    public ParsedSignal parseEntry(String text, String ocr, Long messageId) {
        String t = normalize(text);
        String o = normalize(ocr);

        // Safety net: الـ Gate يفترض أن "دخول" موجودة، نتأكد
        if (!ENTRY_TRIGGER.matcher(t).find()) {
            log.warn("parseEntry: no entry trigger in text (should not reach here) | msgId={}", messageId);
            return null;
        }

        // ===========================================
        // STEP 1: استخرج TYPE من النص (authoritative)
        // ===========================================
        String optionType = extractTypeFromText(t);
        if (optionType == null) {
            log.warn("parseEntry: optionType not in text — try OCR fallback | msgId={}", messageId);
            optionType = extractTypeFromText(o);   // fallback اضطراري
        }
        if (optionType == null) {
            log.warn("parseEntry: SKIP — no CALL/PUT anywhere | msgId={}", messageId);
            return null;
        }

        // ===========================================
        // STEP 2: استخرج STRIKE + EXPIRY من OCR
        // ===========================================
        ContractData cd = extractContractDataFromOcr(o);

        // لو OCR ناقص، جرب النص (للحالة: نص فقط بدون صورة)
        if (cd.strike == null || cd.expiry == null) {
            ContractData fromText = extractContractDataFromText(t);
            if (cd.strike == null) cd.strike = fromText.strike;
            if (cd.expiry == null) cd.expiry = fromText.expiry;
        }

        // ===========================================
        // STEP 3: استخرج PRICE
        // ===========================================
        BigDecimal price = extractInlinePrice(t);   // نص أولاً
        if (price == null) price = extractLivePriceFromOcr(o);   // OCR fallback

        // ===========================================
        // STEP 4: VALIDATION
        // ===========================================
        if (cd.strike == null || cd.expiry == null || price == null) {
            log.warn("parseEntry: SKIP — incomplete data | msgId={} type={} strike={} expiry={} price={}",
                    messageId, optionType, cd.strike, cd.expiry, price);
            return null;
        }

        if (!isValidStrike(cd.strike)) {
            log.warn("parseEntry: SKIP — strike out of bounds [{}] | msgId={}", cd.strike, messageId);
            return null;
        }
        if (!isValidPrice(price)) {
            log.warn("parseEntry: SKIP — price out of bounds [{}] | msgId={}", price, messageId);
            return null;
        }
        if (cd.expiry.isBefore(LocalDate.now())) {
            log.warn("parseEntry: SKIP — expiry in past [{}] | msgId={}", cd.expiry, messageId);
            return null;
        }

        return ParsedSignal.builder()
                .signalType(SignalType.ENTRY)
                .telegramMessageId(messageId)
                .symbol(cd.symbol != null ? cd.symbol : "SPXW")
                .optionType(optionType)
                .strike(cd.strike)
                .expiryDate(cd.expiry)
                .entryPrice(price)
                .rawText(text + (ocr != null && !ocr.isBlank() ? "\n---OCR---\n" + ocr : ""))
                .build();
    }

    /**
     * Parse reply message (UPDATE | CANCEL).
     */
    public ParsedSignal parseReply(String text, Long messageId, Long replyToId) {
        if (replyToId == null) return null;
        String t = normalize(text);
        if (t.isBlank()) return null;

        // CANCEL له الأولوية على UPDATE
        if (CANCEL_KEYWORDS.matcher(t).find()) {
            return ParsedSignal.builder()
                    .signalType(SignalType.CANCEL)
                    .telegramMessageId(messageId)
                    .replyToMessageId(replyToId)
                    .rawText(text)
                    .build();
        }

        BigDecimal newSL = matchValue(SL_UPDATE, t);
        BigDecimal newTP = matchValue(TP_UPDATE, t);
        BigDecimal newEntry = matchValue(ENTRY_UPDATE, t);
        boolean hasUpdateKeyword = UPDATE_KEYWORDS.matcher(t).find();

        if (hasUpdateKeyword || newSL != null || newTP != null || newEntry != null) {
            return ParsedSignal.builder()
                    .signalType(SignalType.UPDATE)
                    .telegramMessageId(messageId)
                    .replyToMessageId(replyToId)
                    .newEntry(newEntry)
                    .newStopLoss(newSL)
                    .newTakeProfit(newTP)
                    .rawText(text)
                    .build();
        }

        return null;
    }

    // =========================================================
    // EXTRACTION HELPERS
    // =========================================================

    private String extractTypeFromText(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = TYPE_FROM_TEXT.matcher(text);
        if (!m.find()) return null;
        String token = m.group(1).toUpperCase(Locale.ROOT);
        return switch (token) {
            case "CALL", "كول" -> "CALL";
            case "PUT", "بوت"  -> "PUT";
            default -> null;
        };
    }

    private ContractData extractContractDataFromOcr(String ocr) {
        ContractData cd = new ContractData();
        if (ocr == null || ocr.isBlank()) return cd;

        Matcher m = CONTRACT_HEADER.matcher(ocr);
        if (m.find()) {
            cd.symbol = m.group(1).toUpperCase(Locale.ROOT);
            cd.strike = parseDecimal(m.group(2).replace(",", ""));
            try {
                int day = Integer.parseInt(m.group(3));
                String monthName = m.group(4).toLowerCase(Locale.ROOT);
                int year = Integer.parseInt(m.group(5));
                if (year < 100) year += 2000;
                Integer month = MONTHS.get(monthName);
                if (month != null) cd.expiry = LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
            return cd;
        }

        // Fallback: strike standalone
        Matcher sm = STRIKE.matcher(ocr);
        if (sm.find()) cd.strike = parseDecimal(sm.group(1));
        return cd;
    }

    private ContractData extractContractDataFromText(String text) {
        ContractData cd = new ContractData();
        if (text == null || text.isBlank()) return cd;

        Matcher sm = STRIKE.matcher(text);
        if (sm.find()) cd.strike = parseDecimal(sm.group(1));

        // Try to find a date "28 Nov 25" anywhere
        Matcher m = CONTRACT_HEADER.matcher(text);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(3));
                int year = Integer.parseInt(m.group(5));
                if (year < 100) year += 2000;
                Integer month = MONTHS.get(m.group(4).toLowerCase(Locale.ROOT));
                if (month != null) cd.expiry = LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }
        return cd;
    }

    private BigDecimal extractInlinePrice(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = INLINE_PRICE.matcher(text);
        if (m.find()) return parseDecimal(m.group(1));
        return null;
    }

    private BigDecimal extractLivePriceFromOcr(String ocr) {
        if (ocr == null || ocr.isBlank()) return null;
        Matcher m = LIVE_PRICE_OCR.matcher(ocr);
        if (m.find()) return parseDecimal(m.group(1));
        return null;
    }

    private BigDecimal matchValue(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (m.find()) return parseDecimal(m.group(1));
        return null;
    }

    private BigDecimal parseDecimal(String s) {
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private boolean isValidStrike(BigDecimal strike) {
        return strike.compareTo(STRIKE_MIN) >= 0 && strike.compareTo(STRIKE_MAX) <= 0;
    }

    private boolean isValidPrice(BigDecimal price) {
        return price.compareTo(PRICE_MIN) >= 0 && price.compareTo(PRICE_MAX) <= 0;
    }

    /** Normalize Arabic-Indic digits + whitespace. */
    private String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        return sb.toString().replaceAll("[ \\t]+", " ").trim();
    }

    private static class ContractData {
        String symbol;
        BigDecimal strike;
        LocalDate expiry;
    }
}
