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
 * Pipeline:
 *   1. Idempotency
 *   2. Extract context + image path
 *   3. OCR (if image)
 *   4. Merge OCR text + caption
 *   5. FastClassifier (rule-based — fast & free for clear patterns)
 *   6. If fast didn't classify → AiSignalParser (with prior-deal context + few-shot)
 *   7. Dispatch
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
            String captionBody = ctx.getBodyText() == null ? "" : ctx.getBodyText().trim();

            if (captionBody.isBlank() && !ctx.isHasImage()) {
                log.debug("Empty message — skip");
                return;
            }

            // OCR on attached image (if any)
            String ocrText = "";
            if (ctx.isHasImage() && imagePath != null && !imagePath.isBlank()) {
                try {
                    byte[] bytes = imageService.loadImageBytes(imagePath);
                    ocrText = ocrService.extractText(bytes);
                    log.info("OCR extracted {} chars", ocrText.length());
                } catch (Exception e) {
                    log.error("OCR failed", e);
                }
            }

            String mergedBody = (ocrText + "\n" + captionBody).trim();
            ctx.setBodyText(mergedBody);
            ctx.setFullText((ctx.getHeaderText() + "\n\n" + mergedBody).trim());
            ctx.setHasImage(false);
            ctx.setImagePath(null);

            // STAGE 1 — Fast rule-based classifier
            ParsedSignal signal = fastClassifier.tryClassify(
                    ctx.getFullText(),
                    ctx.getMessageId(),
                    ctx.getReplyToMessageId());

            if (signal != null) {
                long t = Duration.between(start, Instant.now()).toMillis();
                log.info("⚡ FAST classifier matched | type={} latencyMs={}",
                        signal.getSignalType(), t);
            } else {
                // STAGE 2 — AI parser
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
