package com.mod98.alpaca.spx.service.telegram;

import com.mod98.alpaca.spx.service.IdempotencyService;
import com.mod98.alpaca.spx.service.ocr.AwsOcrService;
import com.mod98.alpaca.spx.service.signal.AiSignalParser;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramMessageHandler {

    private final TelegramMessageExtractor extractor;
    private final TelegramImageService imageService;
    private final AwsOcrService ocrService;
    private final AiSignalParser aiSignalParser;
    private final SignalDispatcher signalDispatcher;
    private final IdempotencyService idempotency;

    /**
     * @Async("telegramExecutor") — كل رسالة تيليجرام تُعالَج على thread منفصل،
     * لا تحجز TDLib callback thread.
     */
    @Async("telegramExecutor")
    public void onTelegramMessage(TdApi.Message msg, String imagePath) {
        Instant start = Instant.now();
        long messageId = msg.id;
        MDC.put("messageId", String.valueOf(messageId));

        try {
            if (!idempotency.tryAcquire(messageId)) {
                return; // سبق ما تعالجت
            }

            TelegramSignalContext ctx = extractor.extract(msg, imagePath);
            String body = ctx.getBodyText() == null ? "" : ctx.getBodyText().trim();

            if (body.isBlank() && !ctx.isHasImage()) {
                log.debug("Skipping empty message");
                return;
            }

            // OCR
            String ocrText = "";
            if (ctx.isHasImage() && imagePath != null && !imagePath.isBlank()) {
                try {
                    byte[] bytes = imageService.loadImageBytes(imagePath);
                    ocrText = ocrService.extractText(bytes);
                } catch (Exception e) {
                    log.error("OCR failed", e);
                }
            }

            String mergedBody = (ocrText + "\n" + body).trim();
            ctx.setBodyText(mergedBody);
            ctx.setFullText((ctx.getHeaderText() + "\n\n" + mergedBody).trim());
            ctx.setHasImage(false);
            ctx.setImagePath(null);

            ParsedSignal signal = aiSignalParser.parse(ctx);
            if (signal == null || signal.getSignalType() == null) {
                log.warn("ParsedSignal null — skip dispatch");
                return;
            }

            long parseMs = Duration.between(start, Instant.now()).toMillis();
            log.info("Parsed signal | type={} symbol={} strike={} parseMs={}",
                    signal.getSignalType(), signal.getSymbol(), signal.getStrike(), parseMs);

            signalDispatcher.dispatch(signal);

        } catch (Exception e) {
            log.error("Telegram message processing failed | msgId={}", messageId, e);
        } finally {
            MDC.remove("messageId");
        }
    }
}
