package com.mod98.alpaca.spx.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mod98.alpaca.spx.config.OpenAIClient;
import com.mod98.alpaca.spx.service.telegram.TelegramSignalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AiSignalParser {

    private static final Logger log = LoggerFactory.getLogger(AiSignalParser.class);

    private final OpenAIClient openAIClient;
    private final ObjectMapper mapper;

    public AiSignalParser(OpenAIClient openAIClient, ObjectMapper mapper) {
        this.openAIClient = openAIClient;
        this.mapper = mapper;
    }

    public ParsedSignal parse(TelegramSignalContext ctx) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(ctx);

        String raw;
        try {
            log.info("🤖 OpenAI request sent at: {} | messageId={}",
                    Instant.now(), ctx.getMessageId());

            raw = openAIClient.chat(systemPrompt, userPrompt);

            log.info("🤖 OpenAI response received at: {} | messageId={}",
                    Instant.now(), ctx.getMessageId());

        } catch (Exception e) {
            throw new RuntimeException("Failed calling OpenAI in AiSignalParser", e);
        }

        try {
            String jsonOnly = extractJsonObject(raw);
            String normalized = jsonOnly.replace(": \"null\"", ": null");

            ParsedSignal signal = mapper.readValue(normalized, ParsedSignal.class);

            signal.setTelegramMessageId(ctx.getMessageId());
            signal.setReplyToMessageId(ctx.getReplyToMessageId());
            signal.setRawText(ctx.getFullText());

            log.info("📦 Parsed signal JSON at: {} | messageId={} | type={} | entrySignalPrice={}",
                    Instant.now(),
                    signal.getTelegramMessageId(),
                    signal.getSignalType(),
                    signal.getEntrySignalPrice());

            return signal;

        } catch (Exception e) {
            log.error("Failed to parse signal JSON: {}", raw, e);
            throw new RuntimeException("Failed to parse signal JSON", e);
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildUserPrompt(TelegramSignalContext ctx) {
        return """
            نص إشارة تداول بالعربية يحتوي على صورة كارد SPXW + شرح:

            %s

            استخرج البيانات وارجع JSON فقط كما في التعليمات.
            """.formatted(ctx.getFullText());
    }

    private String buildSystemPrompt() {
        return """
            You extract SPX/SPXW option trading signals from Arabic text.

            Return ONLY this JSON:

            {
              "signalType": "PREPARE | PREPARE_WITH_DATE | ENTRY | PRICE_ALERT | CANCEL | UPDATE | EXIT",
              "symbol": "SPXW or SPX or null",
              "optionType": "CALL or PUT or null",
              "strike": 6810.0,
              "expiryDate": "2025-11-28",
              "preparePrice": 3.30,
              "entrySignalPrice": 3.30,
              "updatePrice": 3.40,
              "priceAlert": 3.70
            }

            Output ONLY the JSON object. No explanations.
            """;
    }

}
