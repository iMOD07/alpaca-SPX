package com.mod98.alpaca.spx.service.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mod98.alpaca.spx.service.IdempotencyService;
import com.mod98.alpaca.spx.service.ocr.AwsOcrService;
import com.mod98.alpaca.spx.service.signal.MessageShapeGate;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.mod98.alpaca.spx.service.signal.SignalParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pipeline لمعالجة رسائل Telegram — مطبّق المتطلبات v2.
 *
 * Flow:
 *   1. Idempotency check
 *   2. Shape Gate (يميّز preparation_message عن entry)
 *   3. OCR (لو فيه صورة و قرر الـ Gate إنه PARSE_ENTRY)
 *   4. SignalParser
 *   5. Decision JSON (audit log)
 *   6. Dispatch
 *
 * مبدأ التصميم: kill-early + structured audit.
 * كل رسالة تُسجّل decision JSON واضح في الـ logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessor {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final IdempotencyService idempotency;
    private final MessageShapeGate shapeGate;
    private final AwsOcrService ocrService;
    private final SignalParser parser;
    private final EntryHandler entryHandler;
    private final UpdateHandler updateHandler;
    private final CancelHandler cancelHandler;

    /**
     * نقطة الدخول للـ pipeline الجديد.
     * تُستدعى من {@code TelegramMessageHandler} (الذي يعمل بالفعل على
     * {@code telegramExecutor} عبر @Async) — لذا لا نحتاج @Async هنا.
     */
    public void process(TelegramMessage msg) {
        MDC.put("msgId", String.valueOf(msg.messageId()));
        try {
            ProcessResult result = doProcess(msg);
            logDecision(msg, result);
        } catch (Exception e) {
            log.error("Pipeline error | msgId={}", msg.messageId(), e);
        } finally {
            MDC.remove("msgId");
        }
    }

    private ProcessResult doProcess(TelegramMessage msg) {
        // STEP 1: Idempotency
        if (!idempotency.tryAcquire(msg.messageId())) {
            return ProcessResult.skip("duplicate");
        }

        // STEP 2: Shape Gate (يحتاج النص — مش بس flag)
        boolean hasImage = msg.imagePath() != null && !msg.imagePath().isBlank();
        boolean isReply = msg.replyToMessageId() != null;

        MessageShapeGate.Decision decision = shapeGate.classify(hasImage, msg.text(), isReply);

        if (decision != MessageShapeGate.Decision.PARSE_ENTRY
                && decision != MessageShapeGate.Decision.PARSE_REPLY) {
            return ProcessResult.skip(MessageShapeGate.reasonOf(decision));
        }

        // STEP 3: OCR (فقط للـ PARSE_ENTRY مع صورة)
        String ocrText = "";
        if (hasImage && decision == MessageShapeGate.Decision.PARSE_ENTRY) {
            try {
                ocrText = ocrService.extractText(msg.imageBytes());
                log.info("OCR extracted {} chars", ocrText.length());
            } catch (Exception e) {
                log.error("OCR failed — continuing without it", e);
            }
        }

        // STEP 4: Parse
        ParsedSignal signal = (decision == MessageShapeGate.Decision.PARSE_REPLY)
                ? parser.parseReply(msg.text(), msg.messageId(), msg.replyToMessageId())
                : parser.parseEntry(msg.text(), ocrText, msg.messageId());

        if (signal == null) {
            return ProcessResult.skip("unclear");
        }

        // STEP 5: Dispatch
        switch (signal.getSignalType()) {
            case ENTRY -> {
                if (!signal.isValidEntry()) {
                    return ProcessResult.skip("incomplete_entry");
                }
                entryHandler.handle(signal);
                return ProcessResult.process(signal);
            }
            case UPDATE -> {
                updateHandler.handle(signal);
                return ProcessResult.process(signal);
            }
            case CANCEL -> {
                cancelHandler.handle(signal);
                return ProcessResult.process(signal);
            }
        }
        return ProcessResult.skip("unhandled");
    }

    /**
     * يطبع decision JSON واضح في الـ logs — للـ audit/debugging.
     *
     * Format SKIP:
     *   {"action":"skip","reason":"preparation_message","msgId":12345}
     *
     * Format PROCESS (ENTRY):
     *   {"action":"process","type":"CALL","strike":6810,"expiry":"2026-05-28",
     *    "entryPrice":3.30,"msgId":12345}
     */
    private void logDecision(TelegramMessage msg, ProcessResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (result.skipped()) {
            out.put("action", "skip");
            out.put("reason", result.reason());
            out.put("msgId", msg.messageId());
        } else {
            ParsedSignal s = result.signal();
            out.put("action", "process");
            out.put("signalType", s.getSignalType().name());
            out.put("msgId", msg.messageId());
            if (s.getReplyToMessageId() != null) out.put("replyTo", s.getReplyToMessageId());
            if (s.getOptionType() != null) out.put("type", s.getOptionType());
            if (s.getStrike() != null) out.put("strike", s.getStrike());
            if (s.getExpiryDate() != null) out.put("expiry", s.getExpiryDate().toString());
            if (s.getEntryPrice() != null) out.put("entryPrice", s.getEntryPrice());
            if (s.getNewEntry() != null) out.put("newEntry", s.getNewEntry());
            if (s.getNewStopLoss() != null) out.put("newSL", s.getNewStopLoss());
            if (s.getNewTakeProfit() != null) out.put("newTP", s.getNewTakeProfit());
        }
        try {
            log.info("DECISION {}", JSON.writeValueAsString(out));
        } catch (JsonProcessingException e) {
            log.info("DECISION {}", out);
        }
    }

    // =========================================================
    // DTOs
    // =========================================================

    public record TelegramMessage(
            long messageId,
            Long replyToMessageId,
            String text,
            String imagePath,
            byte[] imageBytes
    ) {}

    private record ProcessResult(boolean skipped, String reason, ParsedSignal signal) {
        static ProcessResult skip(String reason) {
            return new ProcessResult(true, reason, null);
        }
        static ProcessResult process(ParsedSignal signal) {
            return new ProcessResult(false, null, signal);
        }
    }
}
