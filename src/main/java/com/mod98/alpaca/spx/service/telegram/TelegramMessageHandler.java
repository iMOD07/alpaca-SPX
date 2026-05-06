package com.mod98.alpaca.spx.service.telegram;

import com.mod98.alpaca.spx.service.IdempotencyService;
import com.mod98.alpaca.spx.service.ocr.AwsOcrService;
import com.mod98.alpaca.spx.service.signal.AiSignalParser;
import com.mod98.alpaca.spx.service.signal.FastSignalClassifier;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.mod98.alpaca.spx.service.signal.SignalDispatcher;
import it.tdlight.jni.TdApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Pipeline (shape-aware):
 *   1. Idempotency
 *   2. Extract caption + image path (separated)
 *   3. OCR on image (if any)
 *   4. FastClassifier(caption, ocr, hasImage, msgId, replyTo) — يفصل intent عن data
 *   5. If FastClassifier didn't decide → AiSignalParser (uses merged text + context)
 *   6. Dispatch
 *
 * القاعدة: caption و OCR لا يُدمَجان في string واحد قبل classification.
 * كل واحد له دور:
 *   - caption  = INTENT (action keyword)
 *   - OCR      = DATA (contract/price)
 *   - replyTo  = CONTEXT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramMessageHandler {

    private final TelegramMessageExtractor extractor;
    private final TelegramImageService imageService;
    private final AwsOcrService ocrService;
    private final FastSignalClassifier fastClassifier;
    private final AiSignalParser aiSignalParser;
    private final SignalDispatcher signalDispatcher;
    private final IdempotencyService idempotency;

    @Async("telegramExecutor")
    public void onTelegramMessage(TdApi.Message msg, String imagePath) {
        Instant start = Instant.now();
        long messageId = msg.id;
        MDC.put("messageId", String.valueOf(messageId));

        try {
            if (!idempotency.tryAcquire(messageId)) return;

            TelegramSignalContext ctx = extractor.extract(msg, imagePath);
            String caption = ctx.getBodyText() == null ? "" : ctx.getBodyText().trim();
            boolean hasImage = ctx.isHasImage();

            if (caption.isBlank() && !hasImage) {
                log.debug("Empty message — skip");
                return;
            }

            // OCR على الصورة (إن وجدت)
            String ocrText = "";
            if (hasImage && imagePath != null && !imagePath.isBlank()) {
                try {
                    byte[] bytes = imageService.loadImageBytes(imagePath);
                    ocrText = ocrService.extractText(bytes);
                    log.info("OCR extracted {} chars", ocrText.length());
                } catch (Exception e) {
                    log.error("OCR failed", e);
                }
            }

            // STAGE 1 — Shape-aware FastClassifier
            // ⚠️ caption و ocrText يُمرَّران منفصلين — لا يُدمَجان قبل الـ shape analysis
            ParsedSignal signal = fastClassifier.tryClassify(
                    caption,
                    ocrText,
                    hasImage,
                    ctx.getMessageId(),
                    ctx.getReplyToMessageId());

            if (signal != null) {
                long t = Duration.between(start, Instant.now()).toMillis();
                log.info("⚡ FAST classifier matched | type={} latencyMs={}",
                        signal.getSignalType(), t);
            } else {
                // STAGE 2 — AI parser fallback (يستخدم النص المدموج + context)
                String mergedForAi = (ocrText + "\n" + caption).trim();
                ctx.setBodyText(mergedForAi);
                ctx.setFullText(mergedForAi);
                ctx.setHasImage(false);   // الصورة استُهلكت
                ctx.setImagePath(null);

                signal = aiSignalParser.parse(ctx);
                long t = Duration.between(start, Instant.now()).toMillis();
                if (signal != null) {
                    log.info("🤖 AI classifier matched | type={} latencyMs={}",
                            signal.getSignalType(), t);
                }
            }

            if (signal == null || signal.getSignalType() == null) {
                log.warn("No signal type identified — skip dispatch");
                return;
            }

            signalDispatcher.dispatch(signal);
        } catch (Exception e) {
            log.error("Telegram message processing failed", e);
        } finally {
            MDC.remove("messageId");
        }
    }
}
