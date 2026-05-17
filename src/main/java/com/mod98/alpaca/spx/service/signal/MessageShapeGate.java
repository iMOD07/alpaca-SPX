package com.mod98.alpaca.spx.service.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Gate أول: يحدّد مصير الرسالة بناءً على شكلها + محتواها.
 *
 * Patterns مستخرجة من 214 رسالة حقيقية لقناة عناد (11-15 May 2026).
 *
 * ── PRECEDENCE ORDER (مهم جداً) ──
 *   1. Reply         → PARSE_REPLY
 *   2. Empty         → SKIP_EMPTY
 *   3. Image-only    → SKIP_IMAGE_ONLY
 *   4. ENTRY trigger → PARSE_ENTRY   ← قرار صريح، أعلى أولوية
 *   5. PROFIT_UPDATE → SKIP
 *   6. RESULTS       → SKIP
 *   7. PREPARATION   → SKIP  (قبل TREND لأن PREP فيها "تابع الشارت" أحياناً)
 *   8. TREND         → SKIP
 *   9. UNCLEAR       → SKIP
 *
 * ── النتيجة على 214 رسالة ──
 *   ENTRY:    5  (2.3%)
 *   PROFIT:  186 (86.9%)
 *   PREP:     ~8 (3.7%)
 *   TREND:   ~10 (4.7%)
 *   RESULTS:  2  (0.9%)
 *   IMAGE:    0  (لا أحد في الـ sample كانت صورة بدون نص)
 */
@Slf4j
@Component
public class MessageShapeGate {

    /**
     * ENTRY trigger — قرار دخول صريح.
     *
     * ✅ Matches:
     *   "🟢 دخول CALL 🟢", "🔴 دخول PUT 🔴", "دخول CALL"
     *
     * ❌ Does NOT match:
     *   "💵 سعر الدخول: 3.80"          (لأنه يطلب CALL/PUT بعد "دخول" مباشرة)
     *   "اعادة الدخول بعد تجاوز..."     (نفس السبب)
     */
    private static final Pattern ENTRY_TRIGGER = Pattern.compile(
            "دخول\\s*(call|put|كول|بوت|🟢|🔴)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** تحديثات الربح أثناء/بعد الصفقة. */
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

    /** نتائج يومية ونتائج صفقات. */
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

    /**
     * رسائل التجهيز من admin.
     * Note: "عقد CALL/PUT" بدون "دخول" = preparation.
     */
    private static final Pattern PREPARATION = Pattern.compile(
            "(حط\\s*أمر\\s*التنفيذ|" +
                    "حط\\s*امر\\s*التنفيذ|" +
                    "لا\\s*تنفذ\\s*اعلى|" +
                    "لا\\s*تنفذ\\s*أعلى|" +
                    "أمر\\s*التنفيذ\\s*بالعقد|" +
                    "امر\\s*التنفيذ\\s*بالعقد|" +
                    "خليك\\s*جاهز|" +
                    "كن\\s*مستعد|" +
                    "🔴\\s*عقد\\s*(CALL|PUT)|" +
                    "🟢\\s*عقد\\s*(CALL|PUT)|" +
                    "عقد\\s*(CALL|PUT)\\s*🔴|" +
                    "عقد\\s*(CALL|PUT)\\s*🟢)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** تحليلات + توقيع البوت بدون قرار دخول. */
    private static final Pattern TREND_ANALYSIS = Pattern.compile(
            "(الاتجاه\\s*\\|\\s*Trend|" +
                    "السيولة\\s*\\|\\s*Liquidity|" +
                    "أعلى\\s*بعقود|" +
                    "اعلى\\s*بعقود|" +
                    "هبوط\\s*PUT|" +
                    "صعود\\s*CALL|" +
                    "تابع\\s*الشارت)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public enum Decision {
        SKIP_IMAGE_ONLY,
        SKIP_PREPARATION,
        SKIP_PROFIT_UPDATE,
        SKIP_TREND_ANALYSIS,
        SKIP_RESULTS,
        SKIP_UNCLEAR,
        SKIP_EMPTY,
        PARSE_ENTRY,
        PARSE_REPLY
    }

    public Decision classify(boolean hasImage, String text, boolean isReply) {
        boolean hasText = text != null && !text.isBlank();

        // 1) Reply
        if (isReply) {
            return Decision.PARSE_REPLY;
        }
        // 2) Empty
        if (!hasImage && !hasText) {
            return Decision.SKIP_EMPTY;
        }
        // 3) Image only
        if (hasImage && !hasText) {
            log.info("SHAPE_GATE: IMAGE_ONLY → SKIP");
            return Decision.SKIP_IMAGE_ONLY;
        }

        String t = text.trim();

        // 4) ENTRY — قرار صريح
        if (ENTRY_TRIGGER.matcher(t).find()) {
            log.info("SHAPE_GATE: ENTRY → PARSE_ENTRY | hasImage={}", hasImage);
            return Decision.PARSE_ENTRY;
        }
        // 5) PROFIT_UPDATE
        if (PROFIT_UPDATE.matcher(t).find()) {
            log.info("SHAPE_GATE: PROFIT_UPDATE → SKIP");
            return Decision.SKIP_PROFIT_UPDATE;
        }
        // 6) RESULTS
        if (RESULTS.matcher(t).find()) {
            log.info("SHAPE_GATE: RESULTS → SKIP");
            return Decision.SKIP_RESULTS;
        }
        // 7) PREPARATION (قبل TREND)
        if (PREPARATION.matcher(t).find()) {
            log.info("SHAPE_GATE: PREPARATION → SKIP");
            return Decision.SKIP_PREPARATION;
        }
        // 8) TREND
        if (TREND_ANALYSIS.matcher(t).find()) {
            log.info("SHAPE_GATE: TREND_ANALYSIS → SKIP");
            return Decision.SKIP_TREND_ANALYSIS;
        }

        return Decision.SKIP_UNCLEAR;
    }

    public static String reasonOf(Decision d) {
        return switch (d) {
            case SKIP_IMAGE_ONLY     -> "image_only";
            case SKIP_PREPARATION    -> "preparation_message";
            case SKIP_PROFIT_UPDATE  -> "profit_update";
            case SKIP_TREND_ANALYSIS -> "trend_analysis";
            case SKIP_RESULTS        -> "results";
            case SKIP_UNCLEAR        -> "unclear";
            case SKIP_EMPTY          -> "empty";
            case PARSE_ENTRY         -> "entry";
            case PARSE_REPLY         -> "reply";
        };
    }
}
