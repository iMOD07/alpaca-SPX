package com.mod98.alpaca.spx.service.telegram;

import com.mod98.alpaca.spx.service.processor.MessageProcessor;
import com.mod98.alpaca.spx.service.processor.MessageProcessor.TelegramMessage;
import it.tdlight.jni.TdApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Adapter رفيع بين TDLib و الـ pipeline الجديد ({@link MessageProcessor}).
 *
 * مسؤوليته فقط:
 *   1. استخراج caption + reply + مسار الصورة من رسالة TDLib.
 *   2. تحميل bytes الصورة من القرص (إن وُجدت).
 *   3. بناء {@link TelegramMessage} وتسليمها للـ MessageProcessor.
 *
 * كل المنطق (idempotency, shape gate, OCR, parsing, dispatch) صار داخل
 * MessageProcessor — مصدر حقيقة واحد. هذا الـ handler @Async على
 * telegramExecutor حتى لا يحجب حلقة تحديثات TDLib أثناء قراءة الصورة.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramMessageHandler {

    private final TelegramMessageExtractor extractor;
    private final TelegramImageService imageService;
    private final MessageProcessor messageProcessor;

    @Async("telegramExecutor")
    public void onTelegramMessage(TdApi.Message msg, String imagePath) {
        long messageId = msg.id;
        MDC.put("messageId", String.valueOf(messageId));
        try {
            TelegramSignalContext ctx = extractor.extract(msg, imagePath);

            String caption = ctx.getBodyText() == null ? "" : ctx.getBodyText().trim();
            boolean hasImage = ctx.isHasImage()
                    && ctx.getImagePath() != null && !ctx.getImagePath().isBlank();

            byte[] imageBytes = null;
            if (hasImage) {
                try {
                    imageBytes = imageService.loadImageBytes(ctx.getImagePath());
                } catch (Exception e) {
                    log.error("Failed to load image bytes — proceeding without image | path={}",
                            ctx.getImagePath(), e);
                }
            }

            messageProcessor.process(new TelegramMessage(
                    ctx.getMessageId(),
                    ctx.getReplyToMessageId(),
                    caption,
                    imageBytes != null ? ctx.getImagePath() : null,
                    imageBytes));
        } catch (Exception e) {
            log.error("Telegram message adapter failed | msgId={}", messageId, e);
        } finally {
            MDC.remove("messageId");
        }
    }
}
