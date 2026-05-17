package com.mod98.alpaca.spx.service.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Gate أول: يحدّد مصير الرسالة بناءً على شكلها (shape) + محتواها (intent keywords).
 *
 * Rules (مطابقة للمتطلبات v2):
 *
 * | Shape                                          | Decision           | Reason                  |
 * |------------------------------------------------|--------------------|--------------------------|
 * | Reply (any content)                            | PARSE_REPLY        | UPDATE/CANCEL handling  |
 * | Image only (no text)                           | SKIP               | image_only              |
 * | Text only + preparation keywords + no "دخول"   | SKIP               | preparation_message     |
 * | Text only + "دخول" keyword                     | PARSE_ENTRY        | text-only entry         |
 * | Text + Image + "دخول" keyword                  | PARSE_ENTRY        | main entry scenario     |
 * | Text + Image — no "دخول"                       | SKIP               | preparation_message     |
 * | Empty (no text, no image, no reply)            | SKIP               | empty                   |
 *
 * مبدأ الأولوية: "دخول" keyword هي الـ trigger الوحيد للتنفيذ.
 * بدونها = SKIP، حتى لو فيه صورة وكل بيانات العقد.
 */
@Slf4j
@Component
public class MessageShapeGate {

    /** Entry trigger — لازم تتوفر صراحة لتنفيذ أي صفقة. */
    private static final Pattern ENTRY_TRIGGER = Pattern.compile(
            "\\b(دخول|ادخل|🟢\\s*دخول|🟢دخول)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Preparation indicators — صريحة (لا تخلط مع entry).
     * هذي تظهر في رسائل التجهيز فقط:
     *  - "خليك جاهز / مراقب"
     *  - "لا تنفذ"
     *  - "امر التنفيذ" (instruction, not execution)
     *  - "تجهيز"
     */
    private static final Pattern PREPARATION_KEYWORDS = Pattern.compile(
            "(خليك\\s*جاهز|مراقب|لا\\s*تنفذ|امر\\s*التنفيذ|تجهيز|استعد|كن\\s*مستعد)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public enum Decision {
        SKIP_IMAGE_ONLY,
        SKIP_PREPARATION,
        SKIP_UNCLEAR,
        SKIP_EMPTY,
        PARSE_ENTRY,
        PARSE_REPLY
    }

    public Decision classify(boolean hasImage, String text, boolean isReply) {
        boolean hasText = text != null && !text.isBlank();

        // 1) Reply أولاً — مهما كان محتواه (UPDATE/CANCEL)
        if (isReply) {
            log.debug("SHAPE_GATE: REPLY → PARSE_REPLY");
            return Decision.PARSE_REPLY;
        }

        // 2) فاضية — SKIP
        if (!hasImage && !hasText) {
            log.debug("SHAPE_GATE: EMPTY → SKIP");
            return Decision.SKIP_EMPTY;
        }

        // 3) صورة فقط بدون نص → SKIP صريح (المتطلبات #2)
        if (hasImage && !hasText) {
            log.info("SHAPE_GATE: IMAGE_ONLY → SKIP");
            return Decision.SKIP_IMAGE_ONLY;
        }

        // 4) فيه نص — افحص الـ intent
        String t = text.trim();
        boolean hasEntryTrigger = ENTRY_TRIGGER.matcher(t).find();
        boolean hasPreparation = PREPARATION_KEYWORDS.matcher(t).find();

        // 4a) نص فيه preparation keyword + ما فيه "دخول" → SKIP (المتطلبات #1)
        // ينطبق سواء كان نص فقط أو نص + صورة
        if (hasPreparation && !hasEntryTrigger) {
            log.info("SHAPE_GATE: PREPARATION_MESSAGE → SKIP | hasImage={}", hasImage);
            return Decision.SKIP_PREPARATION;
        }

        // 4b) فيه "دخول" → PROCESS (المتطلبات #3)
        if (hasEntryTrigger) {
            log.info("SHAPE_GATE: ENTRY_TRIGGER detected → PARSE_ENTRY | hasImage={}", hasImage);
            return Decision.PARSE_ENTRY;
        }

        // 4c) نص لا entry ولا preparation — كلام عادي
        log.debug("SHAPE_GATE: UNCLEAR (no trigger) → SKIP");
        return Decision.SKIP_UNCLEAR;
    }

    /** للـ JSON output — يحوّل enum إلى reason string. */
    public static String reasonOf(Decision d) {
        return switch (d) {
            case SKIP_IMAGE_ONLY  -> "image_only";
            case SKIP_PREPARATION -> "preparation_message";
            case SKIP_UNCLEAR     -> "unclear";
            case SKIP_EMPTY       -> "empty";
            case PARSE_ENTRY      -> "entry";
            case PARSE_REPLY      -> "reply";
        };
    }
}
