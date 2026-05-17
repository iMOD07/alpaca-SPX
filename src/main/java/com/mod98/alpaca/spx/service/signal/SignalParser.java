package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser صارم — يستخرج بيانات العقد من السطر الأول للـ OCR فقط.
 *
 * Strategy v2.2:
 *   - OCR card header دائماً في السطر الأول: "SPXW $6,845  28 Nov 25 (W) Call 100"
 *   - السعر اللحظي في السطر الثاني: "3.30 +0.80 +32.00%"
 *   - أي شي بعد كذا (Open, High, Low, Volume) = نتجاهله نهائياً
 *
 * Authority:
 *   - النص (caption) = decision + optionType
 *   - السطر الأول من OCR = symbol + strike + expiry
 *   - السطر الثاني من OCR = price
 *   - باقي الـ OCR = IGNORED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalParser {

    // =========================================================
    // KEYWORDS
    // =========================================================
    private static final Pattern ENTRY_TRIGGER = Pattern.compile(
            "\\b(دخول|ادخل|🟢\\s*دخول)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern TYPE_FROM_TEXT = Pattern.compile(
            "\\b(call|put|كول|بوت)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANCEL_KEYWORDS = Pattern.compile(
            "\\b(الغ(?:اء|ي|)|إلغاء|ignore|cancel|skip|لا\\s*تدخل|اخرج|خروج|بيع|exit|close)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_KEYWORDS = Pattern.compile(
            "\\b(تعديل|تحديث|update|modify|change)\\b", Pattern.CASE_INSENSITIVE);

    // =========================================================
    // STRICT OCR HEADER — السطر الأول فقط
    // =========================================================

    /**
     * يطابق السطر الأول من card فقط:
     *   "SPXW $6,845  28 Nov 25 (W) Call 100"
     *   "SPXW $6,845 28 Nov 25 (W)Call 100"
     *   "SPXW 6845 28-Nov-25 Call"
     *
     * Groups: (1)=SPX/SPXW (2)=strike (3)=day (4)=month (5)=year (6)=Call/Put
     */
    private static final Pattern CARD_HEADER_LINE = Pattern.compile(
            "^\\s*(SPXW?)\\s*\\$?(\\d{4,5}(?:,\\d{3})?)\\s+" +     // SPXW $6,845
                    "(\\d{1,2})[\\s\\-]+([A-Za-z]{3,9})[\\s\\-]+(\\d{2,4})" +    // 28 Nov 25
                    "\\s*\\(?W?\\)?\\s*" +                                    // (W) optional
                    "(Call|Put)",                                              // Call/Put
            Pattern.CASE_INSENSITIVE);

    /**
     * السعر اللحظي — السطر اللي فيه price + (change).
     * يقبل فقط أرقام صغيرة (< 1000) لأن سعر الأوبشن كذا.
     */
    private static final Pattern LIVE_PRICE_LINE = Pattern.compile(
            "^\\s*(\\d{1,3}\\.\\d{2})\\s*[+\\-]?\\d*\\.?\\d*\\s*$",
            Pattern.MULTILINE);

    /** Inline price in caption: "@ 3.30" or "بسعر 3.30" */
    private static final Pattern INLINE_PRICE = Pattern.compile(
            "(?:بسعر|سعر|@|price|at)\\s*[:：]?\\s*(\\d{1,3}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    // Update value patterns
    private static final Pattern SL_UPDATE = Pattern.compile(
            "\\b(?:sl|stop|stoploss|وقف(?:\\s*الخسارة)?)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TP_UPDATE = Pattern.compile(
            "\\b(?:tp|target|takeprofit|الهدف|هدف)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTRY_UPDATE = Pattern.compile(
            "\\b(?:entry|سعر\\s*الدخول)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    // =========================================================
    // VALIDATION BOUNDS
    // =========================================================
    private static final BigDecimal STRIKE_MIN = new BigDecimal("1000");
    private static final BigDecimal STRIKE_MAX = new BigDecimal("10000");
    private static final BigDecimal PRICE_MIN = new BigDecimal("0.05");
    private static final BigDecimal PRICE_MAX = new BigDecimal("500.00");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1),  Map.entry("january", 1),
            Map.entry("feb", 2),  Map.entry("february", 2),
            Map.entry("mar", 3),  Map.entry("march", 3),
            Map.entry("apr", 4),  Map.entry("april", 4),
            Map.entry("may", 5),
            Map.entry("jun", 6),  Map.entry("june", 6),
            Map.entry("jul", 7),  Map.entry("july", 7),
            Map.entry("aug", 8),  Map.entry("august", 8),
            Map.entry("sep", 9),  Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("october", 10),
            Map.entry("nov", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("december", 12)
    );

    // =========================================================
    // PUBLIC API
    // =========================================================

    public ParsedSignal parseEntry(String text, String ocr, Long messageId) {
        String t = normalize(text);
        String o = normalize(ocr);

        // ⚠️ Print OCR للـ debugging — يساعد لو في issue
        log.info("📝 OCR CONTENT for msgId={}:\n=====OCR-START=====\n{}\n=====OCR-END=====",
                messageId, o);

        // Safety net
        if (!ENTRY_TRIGGER.matcher(t).find()) {
            log.warn("parseEntry: no entry trigger | msgId={}", messageId);
            return null;
        }

        // ===========================================
        // STEP 1: optionType من النص
        // ===========================================
        String optionType = extractTypeFromText(t);
        if (optionType == null) {
            optionType = extractTypeFromText(o);
        }
        if (optionType == null) {
            log.warn("parseEntry: SKIP — no CALL/PUT anywhere | msgId={}", messageId);
            return null;
        }

        // ===========================================
        // STEP 2: parse الـ OCR سطر بسطر (صارم)
        // ===========================================
        OcrCard card = parseOcrLineByLine(o);

        if (card.strike == null || card.expiry == null) {
            log.warn("parseEntry: SKIP — OCR card header not found | msgId={} ocrLen={}",
                    messageId, o.length());
            return null;
        }

        log.info("✅ Card parsed | symbol={} strike={} expiry={} type={}",
                card.symbol, card.strike, card.expiry, card.cardType);

        // ===========================================
        // STEP 3: السعر
        // ===========================================
        BigDecimal price = extractInlinePrice(t);   // النص أولاً
        if (price == null) price = card.price;       // ثم OCR price line

        // ===========================================
        // STEP 4: التحقق إن النص والصورة متفقين على type
        // ===========================================
        if (card.cardType != null && !card.cardType.equalsIgnoreCase(optionType)) {
            log.warn("⚠️ Type mismatch: text={} card={} — using TEXT (authoritative)",
                    optionType, card.cardType);
        }

        // ===========================================
        // STEP 5: VALIDATION صارم
        // ===========================================
        if (!isValidStrike(card.strike)) {
            log.warn("parseEntry: SKIP — strike out of bounds [{}] | msgId={}", card.strike, messageId);
            return null;
        }
        if (price == null || !isValidPrice(price)) {
            log.warn("parseEntry: SKIP — invalid price [{}] | msgId={}", price, messageId);
            return null;
        }
        if (!isValidExpiry(card.expiry)) {
            log.warn("parseEntry: SKIP — invalid expiry [{}] | msgId={}", card.expiry, messageId);
            return null;
        }

        return ParsedSignal.builder()
                .signalType(SignalType.ENTRY)
                .telegramMessageId(messageId)
                .symbol(card.symbol)
                .optionType(optionType)
                .strike(card.strike)
                .expiryDate(card.expiry)
                .entryPrice(price)
                .rawText(text + "\n---OCR---\n" + ocr)
                .build();
    }

    public ParsedSignal parseReply(String text, Long messageId, Long replyToId) {
        if (replyToId == null) return null;
        String t = normalize(text);
        if (t.isBlank()) return null;

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

        if (UPDATE_KEYWORDS.matcher(t).find() || newSL != null || newTP != null || newEntry != null) {
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
    // CORE: parse OCR سطر بسطر
    // =========================================================
    private OcrCard parseOcrLineByLine(String ocr) {
        OcrCard card = new OcrCard();
        if (ocr == null || ocr.isBlank()) return card;

        String[] lines = ocr.split("\\r?\\n");

        // أول 3 أسطر فقط — لأن card header دائماً في الأعلى
        int maxLines = Math.min(lines.length, 5);

        for (int i = 0; i < maxLines; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // محاولة 1: card header
            if (card.strike == null) {
                Matcher m = CARD_HEADER_LINE.matcher(line);
                if (m.find()) {
                    card.symbol = m.group(1).toUpperCase(Locale.ROOT);
                    if (!card.symbol.equals("SPXW")) card.symbol = "SPXW";  // normalize SPX→SPXW

                    String strikeStr = m.group(2).replace(",", "");
                    card.strike = parseDecimal(strikeStr);

                    try {
                        int day = Integer.parseInt(m.group(3));
                        String monthName = m.group(4).toLowerCase(Locale.ROOT);
                        int year = Integer.parseInt(m.group(5));
                        if (year < 100) year += 2000;
                        Integer month = MONTHS.get(monthName);
                        if (month != null) {
                            card.expiry = LocalDate.of(year, month, day);
                        }
                    } catch (Exception e) {
                        log.warn("Date parse failed in line: [{}]", line);
                    }
                    card.cardType = m.group(6).toUpperCase(Locale.ROOT);
                    continue;
                }
            }

            // محاولة 2: السعر اللحظي (3.30 +0.80 etc)
            if (card.price == null) {
                Matcher pm = LIVE_PRICE_LINE.matcher(line);
                if (pm.find()) {
                    BigDecimal candidate = parseDecimal(pm.group(1));
                    // فلتر: السعر يجب يكون في نطاق معقول للأوبشن
                    if (candidate != null && isValidPrice(candidate)) {
                        card.price = candidate;
                    }
                }
            }

            // لو لقينا strike + expiry + price → خلاص
            if (card.strike != null && card.expiry != null && card.price != null) break;
        }

        return card;
    }

    // =========================================================
    // HELPERS
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

    private BigDecimal extractInlinePrice(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = INLINE_PRICE.matcher(text);
        if (m.find()) {
            BigDecimal p = parseDecimal(m.group(1));
            return (p != null && isValidPrice(p)) ? p : null;
        }
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
        return strike != null
                && strike.compareTo(STRIKE_MIN) >= 0
                && strike.compareTo(STRIKE_MAX) <= 0;
    }

    private boolean isValidPrice(BigDecimal price) {
        return price != null
                && price.compareTo(PRICE_MIN) >= 0
                && price.compareTo(PRICE_MAX) <= 0;
    }

    /**
     * Expiry validation:
     *  - مش في الماضي
     *  - مش سبت/أحد (الأسواق مغلقة)
     *  - أقل من 365 يوم في المستقبل
     */
    private boolean isValidExpiry(LocalDate expiry) {
        if (expiry == null) return false;
        LocalDate today = LocalDate.now();
        if (expiry.isBefore(today)) return false;
        if (expiry.isAfter(today.plusDays(365))) return false;
        DayOfWeek dow = expiry.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            log.warn("Expiry on weekend: {} ({})", expiry, dow);
            return false;
        }
        return true;
    }

    /** Normalize Arabic-Indic digits + whitespace + keep newlines. */
    private String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        // احفظ الـ newlines (مهمة للـ line-by-line parsing!)
        return sb.toString().replaceAll("[ \\t]+", " ").trim();
    }

    /** نتيجة parse OCR card. */
    private static class OcrCard {
        String symbol;
        BigDecimal strike;
        LocalDate expiry;
        String cardType;     // CALL/PUT من الصورة (للتحقق فقط)
        BigDecimal price;
    }
}
