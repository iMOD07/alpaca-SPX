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
 * Parser موحّد لرسائل قناة عناد.
 *
 * Authority: النص فقط. الصور تُتجاهَل تماماً (watermark كثيف يفسد OCR).
 *
 * Inputs handled:
 *   1. PREP    → استخراج: optionType, strike, entryPrice, expiry
 *   2. ENTRY   → استخراج: optionType فقط (الباقي يجي من PREP المطابقة)
 *   3. REPLY   → CANCEL / تعديل تاريخ / تعديل strike
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalParser {

    // =========================================================
    // PREP PATTERNS
    // =========================================================

    /** نوع العقد من PREP: "🟢 عقد CALL 🟢" أو "🔴 عقد PUT 🔴" */
    private static final Pattern PREP_OPTION_TYPE = Pattern.compile(
            "عقد\\s*(CALL|PUT)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Strike من PREP: "🎯 استرايك : 7470" */
    private static final Pattern PREP_STRIKE = Pattern.compile(
            "(?:استرايك|إسترايك)\\s*[:：]\\s*(\\d{4,5})",
            Pattern.UNICODE_CASE);

    /** Price من PREP: "💰 حط أمر التنفيذ بالعقد بسعر : 3.9" */
    private static final Pattern PREP_PRICE = Pattern.compile(
            "بسعر\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.UNICODE_CASE);

    /** التاريخ في PREP: "🗓 بتاريخ : اليوم" أو "غداً" أو "14 مايو" */
    private static final Pattern PREP_DATE = Pattern.compile(
            "بتاريخ\\s*[:：]?\\s*(.+?)(?:\\n|$)",
            Pattern.UNICODE_CASE);

    // =========================================================
    // ENTRY PATTERNS
    // =========================================================

    /** ENTRY type: "🟢 دخول CALL 🟢" أو "🔴 دخول PUT 🔴" */
    private static final Pattern ENTRY_OPTION_TYPE = Pattern.compile(
            "دخول\\s*(CALL|PUT|كول|بوت)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // =========================================================
    // REPLY PATTERNS (cancel / date-fix / strike-update)
    // =========================================================

    /** Cancel reply: "إلغاء أمر التنفيذ" */
    private static final Pattern REPLY_CANCEL = Pattern.compile(
            "(إلغاء\\s*أمر|الغاء\\s*أمر|لم\\s*يحقق\\s*دخول)",
            Pattern.UNICODE_CASE);

    /** Date fix reply: "بتاريخ غداً 14 مايو" */
    private static final Pattern REPLY_DATE = Pattern.compile(
            "بتاريخ\\s*(.+)",
            Pattern.UNICODE_CASE);

    /** Strike update reply: "تحديث استرايك العقد 6920" */
    private static final Pattern REPLY_STRIKE_UPDATE = Pattern.compile(
            "تحديث\\s*استرايك\\s*(?:العقد)?\\s*(\\d{4,5})",
            Pattern.UNICODE_CASE);

    // =========================================================
    // DATE KEYWORDS
    // =========================================================
    private static final Pattern DAY_NUMBER = Pattern.compile("(\\d{1,2})");

    private static final Map<String, Integer> MONTHS_AR = Map.ofEntries(
            Map.entry("يناير", 1),  Map.entry("فبراير", 2), Map.entry("مارس", 3),
            Map.entry("أبريل", 4),  Map.entry("ابريل", 4),  Map.entry("مايو", 5),
            Map.entry("يونيو", 6),  Map.entry("يوليو", 7),  Map.entry("أغسطس", 8),
            Map.entry("اغسطس", 8),  Map.entry("سبتمبر", 9),
            Map.entry("أكتوبر", 10), Map.entry("اكتوبر", 10),
            Map.entry("نوفمبر", 11), Map.entry("ديسمبر", 12)
    );

    // =========================================================
    // VALIDATION
    // =========================================================
    private static final BigDecimal STRIKE_MIN = new BigDecimal("1000");
    private static final BigDecimal STRIKE_MAX = new BigDecimal("10000");
    private static final BigDecimal PRICE_MIN = new BigDecimal("0.05");
    private static final BigDecimal PRICE_MAX = new BigDecimal("500.00");

    // =========================================================
    // PUBLIC API
    // =========================================================

    /**
     * Parse PREP message:
     *   "🟢 عقد CALL 🟢
     *    🗓 بتاريخ : اليوم
     *    🎯 استرايك : 7470
     *    💰 حط أمر التنفيذ بالعقد بسعر : 3.9
     *    ❌ لا تنفذ اعلى من سعر التنفيذ"
     */
    public ParsedSignal parsePrep(String text, Long messageId) {
        String t = normalize(text);

        String optionType = matchOptionType(PREP_OPTION_TYPE, t);
        BigDecimal strike = matchDecimal(PREP_STRIKE, t);
        BigDecimal price = matchDecimal(PREP_PRICE, t);
        LocalDate expiry = parseDateFromPrep(t);

        if (optionType == null || strike == null || price == null || expiry == null) {
            log.warn("PREP incomplete | msgId={} type={} strike={} price={} expiry={}",
                    messageId, optionType, strike, price, expiry);
            return null;
        }
        if (!isValidStrike(strike) || !isValidPrice(price) || !isValidExpiry(expiry)) {
            log.warn("PREP invalid values | msgId={} strike={} price={} expiry={}",
                    messageId, strike, price, expiry);
            return null;
        }

        return ParsedSignal.builder()
                .signalType(SignalType.PREP)
                .telegramMessageId(messageId)
                .symbol("SPXW")
                .optionType(optionType)
                .strike(strike)
                .entryPrice(price)
                .expiryDate(expiry)
                .rawText(text)
                .build();
    }

    /**
     * Parse ENTRY message — استخراج optionType فقط.
     * الباقي يأتي من PREP المطابقة في DB.
     */
    public ParsedSignal parseEntry(String text, Long messageId) {
        String t = normalize(text);
        String optionType = matchOptionType(ENTRY_OPTION_TYPE, t);
        if (optionType == null) {
            log.warn("ENTRY: option type not found | msgId={}", messageId);
            return null;
        }
        return ParsedSignal.builder()
                .signalType(SignalType.ENTRY)
                .telegramMessageId(messageId)
                .optionType(optionType)
                .rawText(text)
                .build();
    }

    /**
     * Parse REPLY message (cancel / date fix / strike update).
     * يتجاهل SL/TP updates لأن المستخدم ثابت على $100.
     */
    public ParsedSignal parseReply(String text, Long messageId, Long replyToId) {
        if (replyToId == null) return null;
        String t = normalize(text);
        if (t.isBlank()) return null;

        // 1) CANCEL
        if (REPLY_CANCEL.matcher(t).find()) {
            return ParsedSignal.builder()
                    .signalType(SignalType.CANCEL)
                    .telegramMessageId(messageId)
                    .replyToMessageId(replyToId)
                    .rawText(text)
                    .build();
        }

        // 2) Strike update
        Matcher sm = REPLY_STRIKE_UPDATE.matcher(t);
        if (sm.find()) {
            BigDecimal newStrike = new BigDecimal(sm.group(1));
            if (!isValidStrike(newStrike)) return null;
            return ParsedSignal.builder()
                    .signalType(SignalType.UPDATE)
                    .telegramMessageId(messageId)
                    .replyToMessageId(replyToId)
                    .strike(newStrike)
                    .rawText(text)
                    .build();
        }

        // 3) Date fix
        Matcher dm = REPLY_DATE.matcher(t);
        if (dm.find()) {
            String dateText = dm.group(1).trim();
            LocalDate newExpiry = parseRelativeDate(dateText);
            if (newExpiry != null && isValidExpiry(newExpiry)) {
                return ParsedSignal.builder()
                        .signalType(SignalType.UPDATE)
                        .telegramMessageId(messageId)
                        .replyToMessageId(replyToId)
                        .expiryDate(newExpiry)
                        .rawText(text)
                        .build();
            }
        }

        // 4) SL/TP updates → نتجاهل (نلتزم بـ $100)
        log.debug("Reply ignored — no actionable content | msgId={} text={}",
                messageId, t.substring(0, Math.min(t.length(), 80)));
        return null;
    }

    // =========================================================
    // DATE PARSING
    // =========================================================

    private LocalDate parseDateFromPrep(String text) {
        Matcher m = PREP_DATE.matcher(text);
        if (!m.find()) return null;
        return parseRelativeDate(m.group(1).trim());
    }

    private LocalDate parseRelativeDate(String dateText) {
        if (dateText == null || dateText.isBlank()) return null;
        String d = dateText.trim();
        LocalDate today = LocalDate.now();

        // "اليوم"
        if (d.contains("اليوم")) return today;
        // "غداً" / "غدا"
        if (d.contains("غداً") || d.contains("غدا")) return today.plusDays(1);

        // "14 مايو" / "14 أبريل" / "20 أبريل"
        int day = -1;
        Integer month = null;
        for (Map.Entry<String, Integer> e : MONTHS_AR.entrySet()) {
            if (d.contains(e.getKey())) {
                month = e.getValue();
                break;
            }
        }
        Matcher dm = DAY_NUMBER.matcher(d);
        if (dm.find()) {
            try { day = Integer.parseInt(dm.group(1)); } catch (Exception ignored) {}
        }

        if (month != null && day >= 1 && day <= 31) {
            int year = today.getYear();
            // لو الـ date محسوبة في الماضي، خذ السنة الجاية
            try {
                LocalDate candidate = LocalDate.of(year, month, day);
                if (candidate.isBefore(today.minusDays(7))) {
                    candidate = LocalDate.of(year + 1, month, day);
                }
                return candidate;
            } catch (Exception e) {
                log.warn("Invalid date: day={} month={}", day, month);
            }
        }

        return null;
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private String matchOptionType(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        String t = m.group(1).toUpperCase(Locale.ROOT);
        return switch (t) {
            case "CALL", "كول" -> "CALL";
            case "PUT", "بوت"  -> "PUT";
            default -> null;
        };
    }

    private BigDecimal matchDecimal(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        try { return new BigDecimal(m.group(1)); } catch (Exception e) { return null; }
    }

    private boolean isValidStrike(BigDecimal s) {
        return s != null && s.compareTo(STRIKE_MIN) >= 0 && s.compareTo(STRIKE_MAX) <= 0;
    }

    private boolean isValidPrice(BigDecimal p) {
        return p != null && p.compareTo(PRICE_MIN) >= 0 && p.compareTo(PRICE_MAX) <= 0;
    }

    private boolean isValidExpiry(LocalDate e) {
        if (e == null) return false;
        LocalDate today = LocalDate.now();
        if (e.isBefore(today)) return false;
        if (e.isAfter(today.plusDays(365))) return false;
        DayOfWeek dow = e.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    private String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        return sb.toString().replaceAll("[ \\t]+", " ");
    }
}
