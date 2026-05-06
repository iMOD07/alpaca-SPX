package com.mod98.alpaca.spx.service.telegram;

import com.mod98.alpaca.spx.service.ocr.AwsOcrService;
import com.mod98.alpaca.spx.service.signal.AiSignalParser;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.mod98.alpaca.spx.service.signal.SignalDispatcher;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TelegramMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageHandler.class);

    private final TelegramMessageExtractor extractor;
    private final TelegramImageService imageService;
    private final AwsOcrService ocrService;
    private final AiSignalParser aiSignalParser;
    private final SignalDispatcher signalDispatcher;

    public TelegramMessageHandler(TelegramMessageExtractor extractor,
                                  TelegramImageService imageService,
                                  AwsOcrService ocrService,
                                  AiSignalParser aiSignalParser,
                                  SignalDispatcher signalDispatcher) {
        this.extractor = extractor;
        this.imageService = imageService;
        this.ocrService = ocrService;
        this.aiSignalParser = aiSignalParser;
        this.signalDispatcher = signalDispatcher;
    }

    /**
     * يُستدعى من TelegramClientService:
     *  - msg: رسالة تيليجرام الخام
     *  - imagePath: مسار الصورة لو كانت الرسالة تحتوي كارد، أو null لو نص فقط
     */
    public void onTelegramMessage(TdApi.Message msg, String imagePath) {
        TelegramSignalContext ctx = extractor.extract(msg, imagePath);

        log.info("🧾 Built TelegramSignalContext | msgId={} | replyTo={} | hasImage={} ",
                ctx.getMessageId(),
                ctx.getReplyToMessageId(),
                ctx.isHasImage());

        String body = ctx.getBodyText() == null ? "" : ctx.getBodyText().trim();
        if (body.isBlank() && !ctx.isHasImage()) {
            return;
        }

        log.info("📩 Extract started at: {} | hasImage={} | messageId={}",
                Instant.now(), ctx.isHasImage(), ctx.getMessageId());

        // 1) OCR من الصورة لو موجودة
        String ocrText = "";
        if (ctx.isHasImage() && imagePath != null && !imagePath.isBlank()) {
            try {
                byte[] bytes = imageService.loadImageBytes(imagePath);
                ocrText = ocrService.extractText(bytes);
            } catch (Exception e) {
                log.error("Failed to run AWS OCR for msgId={}", ctx.getMessageId(), e);
            }
        }

        // 2) ندمج OCR مع الكابتشن
        String mergedBody = (ocrText + "\n" + body).trim();
        ctx.setBodyText(mergedBody);
        ctx.setFullText((ctx.getHeaderText() + "\n\n" + mergedBody).trim());

        // 3) نطفي الـ hasImage عشان AiSignalParser يستخدم TEXT فقط
        ctx.setHasImage(false);
        ctx.setImagePath(null);

        // 4) نمرره لـ AiSignalParser
        ParsedSignal signal = aiSignalParser.parse(ctx);

        if (signal == null || signal.getSignalType() == null) {
            log.warn("ParsedSignal is null or has null signalType, skip dispatch. messageId={}", ctx.getMessageId());
            return;
        }

        log.info(
                "🎯 Final parsed signal → msgId={} | type={} | symbol={} | optionType={} | strike={} | expiryDate={} | preparePrice={} | entrySignalPrice={} | updatePrice={}",
                signal.getTelegramMessageId(),
                signal.getSignalType(),
                signal.getSymbol(),
                signal.getOptionType(),
                signal.getStrike(),
                signal.getExpiryDate(),
                signal.getPreparePrice(),
                signal.getEntrySignalPrice(),
                signal.getUpdatePrice()
        );

        signalDispatcher.dispatch(signal);
    }
}
