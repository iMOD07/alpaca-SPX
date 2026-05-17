package com.mod98.alpaca.spx.service.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Gate أول: يصنّف رسائل قناة عناد (ICMR Indicators) لـ 9 فئات.
 *
 * Validated على 377 رسالة حقيقية + 22 conversation reply trees.
 *
 * ── PRECEDENCE ORDER ──
 *   1. REPLY (UPDATE/CANCEL على PREP)
 *   2. EMPTY
 *   3. IMAGE_ONLY
 *   4. CANCEL (إلغاء أمر التنفيذ) — قبل PREP لأن CANCEL يحتوي "عقد CALL/PUT"
 *   5. ENTRY (دخول CALL/PUT)
 *   6. PROFIT_UPDATE
 *   7. RESULTS
 *   8. PREP (حط أمر التنفيذ / عقد CALL/PUT)
 *   9. TREND
 *  10. UNCLEAR
 */
@Slf4j
@Component
public class MessageShapeGate {

    /** ENTRY trigger — قرار دخول صريح. */
    private static final Pattern ENTRY_TRIGGER = Pattern.compile(
            "دخول\\s*(call|put|كول|بوت|🟢|🔴)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * CANCEL — admin يلغي PREP لم تتنفذ.
     * Examples: "⚠️إلغاء أمر التنفيذ ⚠️"
     */
    private static final Pattern ADMIN_CANCEL = Pattern.compile(
            "(إلغاء\\s*أمر\\s*التنفيذ|" +
                    "الغاء\\s*أمر\\s*التنفيذ|" +
                    "إلغاء\\s*امر\\s*التنفيذ|" +
                    "الغاء\\s*امر\\s*التنفيذ|" +
                    "لم\\s*يحقق\\s*دخول)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * PREP — admin يجهّز عقد قبل الدخول.
     * Must have: "عقد CALL/PUT" + "حط أمر التنفيذ"
     */
    private static final Pattern PREP = Pattern.compile(
            "(حط\\s*أمر\\s*التنفيذ|" +
                    "حط\\s*امر\\s*التنفيذ|" +
                    "أمر\\s*التنفيذ\\s*بالعقد)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** PROFIT_UPDATE — تحديثات الربح أثناء/بعد الصفقة. */
    private static final Pattern PROFIT_UPDATE = Pattern.compile(
            "(سعر\\s*الدخول\\s*[:：]|" +
                    "السعر\\s*الآن\\s*[:：]|" +
                    "السعر\\s*الان\\s*[:：]|" +
                    "أعلى\\s*سعر\\s*وصل|" +
                    "اعلى\\s*سعر\\s*وصل|" +
                    "الربح\\s*[:：].*[\\$دولار]|" +
                    "يفضل\\s*الخروج\\s*بربح|" +
                    "صفقتنا\\s*اليوم)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** RESULTS — نتائج يومية ونتائج صفقات. */
    private static final Pattern RESULTS = Pattern.compile(
            "(نتائج\\s*روبوت|" +
                    "أرباح\\s*اليوم|" +
                    "ارباح\\s*اليوم|" +
                    "خسائر\\s*اليوم|" +
                    "صافي\\s*الربح|" +
                    "صفقة\\s*خاسرة|" +
                    "صفقة\\s*رابحة|" +
                    "لم\\s*تحقق\\s*ربح)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** TREND — تحليلات بدون قرار دخول. */
    private static final Pattern TREND = Pattern.compile(
            "(الاتجاه\\s*\\|\\s*Trend|" +
                    "السيولة\\s*\\|\\s*Liquidity|" +
                    "أعلى\\s*بعقود|" +
                    "اعلى\\s*بعقود|" +
                    "هبوط\\s*PUT|" +
                    "صعود\\s*CALL|" +
                    "قوة\\s*الاتجاه|" +
                    "السيولة\\s*اللحظية|" +
                    "تابع\\s*الشارت)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public enum Decision {
        SKIP_IMAGE_ONLY,
        SKIP_PROFIT_UPDATE,
        SKIP_TREND,
        SKIP_RESULTS,
        SKIP_UNCLEAR,
        SKIP_EMPTY,
        PARSE_ENTRY,
        PARSE_PREP,
        PARSE_ADMIN_CANCEL,
        PARSE_REPLY
    }

    public Decision classify(boolean hasImage, String text, boolean isReply) {
        boolean hasText = text != null && !text.isBlank();

        // 1) Reply (أعلى أولوية)
        if (isReply) {
            return Decision.PARSE_REPLY;
        }
        if (!hasImage && !hasText) {
            return Decision.SKIP_EMPTY;
        }
        if (hasImage && !hasText) {
            log.info("SHAPE_GATE: IMAGE_ONLY → SKIP");
            return Decision.SKIP_IMAGE_ONLY;
        }

        String t = text.trim();

        // 2) ADMIN_CANCEL (قبل PREP لأن CANCEL message فيه "عقد CALL/PUT")
        if (ADMIN_CANCEL.matcher(t).find()) {
            log.info("SHAPE_GATE: ADMIN_CANCEL → PARSE");
            return Decision.PARSE_ADMIN_CANCEL;
        }

        // 3) ENTRY (قرار صريح)
        if (ENTRY_TRIGGER.matcher(t).find()) {
            log.info("SHAPE_GATE: ENTRY → PARSE");
            return Decision.PARSE_ENTRY;
        }

        // 4) PROFIT_UPDATE
        if (PROFIT_UPDATE.matcher(t).find()) {
            log.info("SHAPE_GATE: PROFIT_UPDATE → SKIP");
            return Decision.SKIP_PROFIT_UPDATE;
        }

        // 5) RESULTS
        if (RESULTS.matcher(t).find()) {
            log.info("SHAPE_GATE: RESULTS → SKIP");
            return Decision.SKIP_RESULTS;
        }

        // 6) PREP
        if (PREP.matcher(t).find()) {
            log.info("SHAPE_GATE: PREP → PARSE");
            return Decision.PARSE_PREP;
        }

        // 7) TREND
        if (TREND.matcher(t).find()) {
            log.info("SHAPE_GATE: TREND → SKIP");
            return Decision.SKIP_TREND;
        }

        return Decision.SKIP_UNCLEAR;
    }

    public static String reasonOf(Decision d) {
        return switch (d) {
            case SKIP_IMAGE_ONLY    -> "image_only";
            case SKIP_PROFIT_UPDATE -> "profit_update";
            case SKIP_TREND         -> "trend_analysis";
            case SKIP_RESULTS       -> "results";
            case SKIP_UNCLEAR       -> "unclear";
            case SKIP_EMPTY         -> "empty";
            case PARSE_ENTRY        -> "entry";
            case PARSE_PREP         -> "prep";
            case PARSE_ADMIN_CANCEL -> "admin_cancel";
            case PARSE_REPLY        -> "reply";
        };
    }
}
