package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based classifier — يحاول استخراج الإشارة بدون AI لو الـ keywords واضحة.
 *
 * يقلل تكلفة OpenAI calls ويعطي latency منخفض جداً للحالات الشائعة.
 * يرجع null لو ما قدر يحسم — وقتها الـ AI parser يتولى.
 *
 * الأنماط المدعومة (مبنية على تحليل قناة SPX):
 *  1. PREPARE: "خليك جاهز" / "امر التنفيذ بالعقد بسعر : X.X" / "استرايك : NNNN"
 *  2. ENTRY:   "🟢 دخول CALL" / "🟢 دخول PUT"
 *  3. UPDATE:  "تحديث الأمر X.X" / "تحديث X.X" (reply)
 *  4. CANCEL:  "الغاء" / "إلغاء" (reply)
 *  5. EXIT:    "خروج" / "اخرج" / "بيع" (reply)
 */
@Slf4j
@Component
public class FastSignalClassifier {

    // ========= Keywords =========
    private static final Pattern PREPARE_KEYWORDS = Pattern.compile(
            "خليك\\s*جاهز|امر\\s*التنفيذ|لا\\s*تنفذ\\s*اعلى|عقد\\s*CALL|عقد\\s*PUT",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTRY_KEYWORDS = Pattern.compile(
            "دخول\\s*CALL|دخول\\s*PUT|دخول\\s+(الان|الآن|🟢)|🟢\\s*دخول",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_KEYWORDS = Pattern.compile(
            "تحديث\\s*(الأمر|الامر|السعر)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANCEL_KEYWORDS = Pattern.compile(
            "الغاء|إلغاء|الغ\\s*الأمر|الغ\\s*الامر", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXIT_KEYWORDS = Pattern.compile(
            "خروج|اخرج|اخروج|بيع\\s*(الآن|الان)?", Pattern.CASE_INSENSITIVE);

    // ========= Field extractors =========
    /** "استرايك : 6810"  أو  "Strike: 6810" */
    private static final Pattern STRIKE_PATTERN = Pattern.compile(
            "(?:استرايك|strike)\\s*[:：]\\s*(\\d{3,5}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** "بسعر : 3.3"  أو  "بسعر 3.3"  أو  "@ 3.3" */
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:بسعر|سعر|@)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** "تحديث الأمر 3.4" — يلتقط الرقم */
    private static final Pattern UPDATE_PRICE_PATTERN = Pattern.compile(
            "تحديث(?:\\s*(?:الأمر|الامر|السعر))?\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Header: "SPXW $6,810 26 Nov 25 (W) Call 100" */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(SPXW?)\\s*\\$?(\\d[\\d,]*)\\s*(\\d{1,2})\\s*([A-Za-z]{3,9})\\s*(\\d{2,4})\\s*\\(?W?\\)?\\s*(Call|Put)",
            Pattern.CASE_INSENSITIVE);

    /** Live price card: "3.30 +0.80 +32.00%" — السعر الكبير في أعلى الكارد */
    private static final Pattern LIVE_PRICE_PATTERN = Pattern.compile(
            "(?m)^\\s*(\\d+\\.\\d+)\\s+\\+\\d");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1),  Map.entry("january", 1),  Map.entry("يناير", 1),
            Map.entry("feb", 2),  Map.entry("february", 2), Map.entry("فبراير", 2),
            Map.entry("mar", 3),  Map.entry("march", 3),    Map.entry("مارس", 3),
            Map.entry("apr", 4),  Map.entry("april", 4),    Map.entry("ابريل", 4),
            Map.entry("may", 5),                            Map.entry("مايو", 5),
            Map.entry("jun", 6),  Map.entry("june", 6),     Map.entry("يونيو", 6),
            Map.entry("jul", 7),  Map.entry("july", 7),     Map.entry("يوليو", 7),
            Map.entry("aug", 8),  Map.entry("august", 8),   Map.entry("اغسطس", 8),
            Map.entry("sep", 9),  Map.entry("september", 9),Map.entry("سبتمبر", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("اكتوبر", 10),
            Map.entry("nov", 11), Map.entry("november", 11),Map.entry("نوفمبر", 11),
            Map.entry("dec", 12), Map.entry("december", 12),Map.entry("ديسمبر", 12)
    );

    /**
     * يحاول استخراج إشارة من النص.
     * يرجع null إذا لم يستطع التصنيف بثقة.
     */
    public ParsedSignal tryClassify(String text, Long messageId, Long replyToMessageId) {
        if (text == null || text.isBlank()) return null;
        String norm = normalize(text);

        // ========= 1) CANCEL (reply only) =========
        if (replyToMessageId != null && CANCEL_KEYWORDS.matcher(norm).find()) {
            log.info("FastClassifier: CANCEL detected | replyTo={}", replyToMessageId);
            return build(SignalType.CANCEL, norm, messageId, replyToMessageId);
        }

        // ========= 2) UPDATE (reply, with price) =========
        if (replyToMessageId != null) {
            Matcher um = UPDATE_PRICE_PATTERN.matcher(norm);
            if (um.find()) {
                ParsedSignal s = build(SignalType.UPDATE, norm, messageId, replyToMessageId);
                s.setUpdatePrice(parseBigDecimal(um.group(1)));
                log.info("FastClassifier: UPDATE detected | newPrice={} replyTo={}",
                        s.getUpdatePrice(), replyToMessageId);
                return s;
            }
        }

        // ========= 3) EXIT (reply) =========
        if (replyToMessageId != null && EXIT_KEYWORDS.matcher(norm).find()) {
            log.info("FastClassifier: EXIT detected | replyTo={}", replyToMessageId);
            return build(SignalType.EXIT, norm, messageId, replyToMessageId);
        }

        // ========= 4) ENTRY (keyword "دخول CALL/PUT") =========
        if (ENTRY_KEYWORDS.matcher(norm).find()) {
            ParsedSignal s = build(SignalType.ENTRY, norm, messageId, replyToMessageId);
            extractContractFields(norm, s);
            // entrySignalPrice من live price card العلوي إن وُجد
            Matcher lp = LIVE_PRICE_PATTERN.matcher(text);
            if (lp.find()) {
                s.setEntrySignalPrice(parseBigDecimal(lp.group(1)));
            }
            log.info("FastClassifier: ENTRY detected | strike={} entryPrice={}",
                    s.getStrike(), s.getEntrySignalPrice());
            return s;
        }

        // ========= 5) PREPARE (keywords: خليك جاهز / امر التنفيذ / استرايك) =========
        if (PREPARE_KEYWORDS.matcher(norm).find()) {
            ParsedSignal s = build(SignalType.PREPARE, norm, messageId, replyToMessageId);
            extractContractFields(norm, s);

            // preparePrice من "بسعر : 3.3"
            Matcher pp = PRICE_PATTERN.matcher(norm);
            if (pp.find()) {
                BigDecimal price = parseBigDecimal(pp.group(1));
                s.setPreparePrice(price);
                s.setEntrySignalPrice(price);
            }
            // مهم: لازم نتأكد إن عندنا strike على الأقل
            if (s.getStrike() != null && s.getPreparePrice() != null) {
                log.info("FastClassifier: PREPARE detected | strike={} price={}",
                        s.getStrike(), s.getPreparePrice());
                return s;
            }
            // ناقص بيانات → دع AI يحاول
            log.debug("FastClassifier: PREPARE keywords but missing fields — defer to AI");
        }

        // ما قدر يحسم
        return null;
    }

    /** يستخرج الـ contract fields من header SPXW $6,810 26 Nov 25 Call */
    private void extractContractFields(String norm, ParsedSignal s) {
        Matcher hm = HEADER_PATTERN.matcher(norm);
        if (hm.find()) {
            s.setSymbol(hm.group(1).toUpperCase(Locale.ROOT));
            String strikeStr = hm.group(2).replace(",", "");
            s.setStrike(parseBigDecimal(strikeStr));
            int day = Integer.parseInt(hm.group(3));
            String monthName = hm.group(4).toLowerCase(Locale.ROOT);
            int year = Integer.parseInt(hm.group(5));
            if (year < 100) year += 2000;
            Integer month = MONTHS.get(monthName);
            if (month != null) {
                try { s.setExpiryDate(LocalDate.of(year, month, day)); } catch (Exception ignored) {}
            }
            s.setOptionType(hm.group(6).toUpperCase(Locale.ROOT));
        }

        // fallback strike: "استرايك : 6810"
        if (s.getStrike() == null) {
            Matcher sm = STRIKE_PATTERN.matcher(norm);
            if (sm.find()) s.setStrike(parseBigDecimal(sm.group(1)));
        }
    }

    private ParsedSignal build(SignalType type, String raw, Long msgId, Long replyTo) {
        ParsedSignal s = new ParsedSignal();
        s.setSignalType(type);
        s.setRawText(raw);
        s.setTelegramMessageId(msgId);
        s.setReplyToMessageId(replyTo);
        return s;
    }

    private BigDecimal parseBigDecimal(String s) {
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /** Normalize Arabic-Indic digits, whitespace */
    private String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));   // Arabic
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰'))); // Persian
            else sb.append(c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
