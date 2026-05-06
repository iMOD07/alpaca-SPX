package com.mod98.alpaca.spx.service.signal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * يحدّد "شكل" الرسالة (structure) قبل أي محاولة classification.
 *
 * هذا حاسم لأن الـ FastClassifier لا يستطيع التمييز بين:
 *  - PRICE_ALERT (image only) — لا تنفّذ
 *  - ENTRY (image + caption "دخول") — نفّذ صفقة
 * من النص المدموج وحده.
 *
 * القاعدة الذهبية:
 *   Caption  = INTENT  (ماذا يطلب المرسل)
 *   Image    = DATA    (تفاصيل العقد والسعر)
 *   Reply    = CONTEXT (على أي صفقة سابقة)
 */
@Slf4j
@Component
public class MessageShapeAnalyzer {

    /** Threshold قصير: caption أقل من 30 حرف = action keyword فقط. */
    private static final int SHORT_CAPTION_LIMIT = 30;

    public Shape analyze(boolean hasImage, String caption, String ocrText, Long replyToMessageId) {
        boolean hasCaption = caption != null && !caption.isBlank();
        boolean isReply = replyToMessageId != null;
        int captionLen = hasCaption ? caption.trim().length() : 0;

        Shape shape;
        // Reply text without image = update/cancel/exit reply
        if (isReply && !hasImage) {
            shape = Shape.REPLY_TEXT_ONLY;
        }
        // Image without caption = price alert (forwarded card)
        else if (hasImage && !hasCaption) {
            shape = Shape.IMAGE_ONLY;
        }
        // Image + short caption = explicit action signal (ENTRY usually)
        else if (hasImage && captionLen < SHORT_CAPTION_LIMIT) {
            shape = Shape.IMAGE_WITH_SHORT_CAPTION;
        }
        // Image + long caption = full PREPARE/instructions
        else if (hasImage) {
            shape = Shape.IMAGE_WITH_LONG_CAPTION;
        }
        // Plain text without image
        else if (hasCaption) {
            shape = Shape.TEXT_ONLY;
        } else {
            shape = Shape.UNKNOWN;
        }

        log.info("Shape={} | hasImage={} captionLen={} ocrLen={} isReply={}",
                shape, hasImage, captionLen, ocrText == null ? 0 : ocrText.length(), isReply);
        return shape;
    }

    public enum Shape {
        REPLY_TEXT_ONLY,            // "تحديث 3.4" / "الغاء" / "خروج"
        IMAGE_ONLY,                 // PRICE_ALERT (forwarded live card)
        IMAGE_WITH_SHORT_CAPTION,   // ENTRY (action keyword in caption)
        IMAGE_WITH_LONG_CAPTION,    // PREPARE (full instructions)
        TEXT_ONLY,                  // standalone text — possibly anything
        UNKNOWN
    }
}
