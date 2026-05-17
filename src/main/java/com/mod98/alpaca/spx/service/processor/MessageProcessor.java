package com.mod98.alpaca.spx.service.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mod98.alpaca.spx.service.IdempotencyService;
import com.mod98.alpaca.spx.service.signal.MessageShapeGate;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import com.mod98.alpaca.spx.service.signal.SignalParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pipeline موحّد لرسائل Telegram — v3 PREP-then-ENTRY workflow.
 *
 * No OCR needed — everything from text.
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
    private final SignalParser parser;
    private final PrepHandler prepHandler;
    private final EntryHandler entryHandler;
    private final ReplyHandler replyHandler;
    private final AdminCancelHandler adminCancelHandler;

    @Async("telegramExecutor")
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
        // Idempotency
        if (!idempotency.tryAcquire(msg.messageId())) {
            return ProcessResult.skip("duplicate");
        }

        // Shape Gate
        boolean hasImage = msg.imagePath() != null && !msg.imagePath().isBlank();
        boolean isReply = msg.replyToMessageId() != null;

        MessageShapeGate.Decision decision = shapeGate.classify(hasImage, msg.text(), isReply);

        // Skip cases — no parsing
        switch (decision) {
            case SKIP_IMAGE_ONLY, SKIP_PROFIT_UPDATE, SKIP_TREND,
                 SKIP_RESULTS, SKIP_UNCLEAR, SKIP_EMPTY -> {
                return ProcessResult.skip(MessageShapeGate.reasonOf(decision));
            }
            default -> { /* fall through to parsing */ }
        }

        // Parse + dispatch
        ParsedSignal signal = switch (decision) {
            case PARSE_PREP         -> parser.parsePrep(msg.text(), msg.messageId());
            case PARSE_ENTRY        -> parser.parseEntry(msg.text(), msg.messageId());
            case PARSE_REPLY        -> parser.parseReply(msg.text(), msg.messageId(), msg.replyToMessageId());
            case PARSE_ADMIN_CANCEL -> ParsedSignal.builder()
                    .telegramMessageId(msg.messageId())
                    .rawText(msg.text())
                    .build();
            default -> null;
        };

        if (signal == null) {
            return ProcessResult.skip("parse_failed");
        }

        // Dispatch to appropriate handler
        switch (decision) {
            case PARSE_PREP -> prepHandler.handle(signal);
            case PARSE_ENTRY -> entryHandler.handle(signal);
            case PARSE_REPLY -> replyHandler.handle(signal);
            case PARSE_ADMIN_CANCEL -> adminCancelHandler.handle(signal);
            default -> { return ProcessResult.skip("no_handler"); }
        }

        return ProcessResult.process(signal, decision);
    }

    private void logDecision(TelegramMessage msg, ProcessResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (result.skipped()) {
            out.put("action", "skip");
            out.put("reason", result.reason());
            out.put("msgId", msg.messageId());
        } else {
            ParsedSignal s = result.signal();
            out.put("action", "process");
            out.put("category", MessageShapeGate.reasonOf(result.decision()));
            out.put("msgId", msg.messageId());
            if (s.getReplyToMessageId() != null) out.put("replyTo", s.getReplyToMessageId());
            if (s.getOptionType() != null) out.put("type", s.getOptionType());
            if (s.getStrike() != null) out.put("strike", s.getStrike());
            if (s.getExpiryDate() != null) out.put("expiry", s.getExpiryDate().toString());
            if (s.getEntryPrice() != null) out.put("entryPrice", s.getEntryPrice());
        }
        try {
            log.info("DECISION {}", JSON.writeValueAsString(out));
        } catch (JsonProcessingException e) {
            log.info("DECISION {}", out);
        }
    }

    // ============================================================
    public record TelegramMessage(
            long messageId,
            Long replyToMessageId,
            String text,
            String imagePath,
            byte[] imageBytes
    ) {}

    private record ProcessResult(
            boolean skipped,
            String reason,
            ParsedSignal signal,
            MessageShapeGate.Decision decision
    ) {
        static ProcessResult skip(String reason) {
            return new ProcessResult(true, reason, null, null);
        }
        static ProcessResult process(ParsedSignal signal, MessageShapeGate.Decision d) {
            return new ProcessResult(false, null, signal, d);
        }
    }
}
