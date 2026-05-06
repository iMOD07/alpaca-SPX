package com.mod98.alpaca.spx.service.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mod98.alpaca.spx.config.OpenAIClient;
import com.mod98.alpaca.spx.service.telegram.TelegramSignalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI parser محسّن:
 *  - يقبل context من الرسالة الـ replied-to
 *  - يستخدم few-shot examples من أنماط القناة الفعلية
 *  - يطلب confidence score من النموذج
 *  - يرفض الإشارات منخفضة الثقة (< 0.7)
 */
@Slf4j
@Service
public class AiSignalParser {

    private final OpenAIClient openAIClient;
    private final ObjectMapper mapper;
    private final ContextBuilder contextBuilder;

    public AiSignalParser(OpenAIClient openAIClient,
                          ObjectMapper mapper,
                          ContextBuilder contextBuilder) {
        this.openAIClient = openAIClient;
        this.mapper = mapper;
        this.contextBuilder = contextBuilder;
    }

    public ParsedSignal parse(TelegramSignalContext ctx) {
        ContextBuilder.MessageContext priorContext =
                contextBuilder.buildContext(ctx.getReplyToMessageId());

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(ctx, priorContext);

        log.info("📝 INPUT (msgId={}) priorContext={}", ctx.getMessageId(),
                priorContext.isEmpty() ? "none" : priorContext.dealId());

        String raw;
        try {
            raw = openAIClient.chat(systemPrompt, userPrompt);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI call failed", e);
        }

        try {
            String jsonOnly = extractJsonObject(raw);
            String normalized = jsonOnly
                    .replace(": \"null\"", ": null")
                    .replace(":\"null\"", ":null");

            ParsedSignal signal = mapper.readValue(normalized, ParsedSignal.class);

            // اقرأ confidence من رد النموذج (نضيفه كـ field غير مُهيكل)
            Double confidence = extractConfidence(normalized);
            if (confidence != null && confidence < 0.7) {
                log.warn("🚫 LOW confidence ({}) — skip dispatch | msgId={} raw={}",
                        confidence, ctx.getMessageId(), raw);
                return null;
            }

            // أكمل من context إذا الـ AI رجع null في حقول مهمة
            enrichFromContext(signal, priorContext);

            signal.setTelegramMessageId(ctx.getMessageId());
            signal.setReplyToMessageId(ctx.getReplyToMessageId());
            signal.setRawText(ctx.getFullText());

            log.info("✅ Parsed | msgId={} type={} symbol={} strike={} price={} confidence={}",
                    signal.getTelegramMessageId(),
                    signal.getSignalType(),
                    signal.getSymbol(),
                    signal.getStrike(),
                    signal.getPreparePrice() != null ? signal.getPreparePrice() : signal.getEntrySignalPrice(),
                    confidence);

            return signal;
        } catch (Exception e) {
            log.error("Failed to parse signal JSON: {}", raw, e);
            return null;
        }
    }

    /** يكمل الـ signal من contextual deal (للرسائل المختصرة جداً) */
    private void enrichFromContext(ParsedSignal s, ContextBuilder.MessageContext ctx) {
        if (ctx.isEmpty()) return;
        if (s.getSymbol() == null && ctx.symbol() != null) s.setSymbol(ctx.symbol());
        if (s.getOptionType() == null && ctx.optionType() != null) s.setOptionType(ctx.optionType());
        if (s.getStrike() == null && ctx.strike() != null) {
            try { s.setStrike(new java.math.BigDecimal(ctx.strike())); } catch (Exception ignored) {}
        }
        if (s.getExpiryDate() == null && ctx.expiryDate() != null) {
            try { s.setExpiryDate(java.time.LocalDate.parse(ctx.expiryDate())); } catch (Exception ignored) {}
        }
    }

    private Double extractConfidence(String json) {
        try {
            var node = mapper.readTree(json);
            if (node.has("confidence") && node.get("confidence").isNumber()) {
                return node.get("confidence").asDouble();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        return (s >= 0 && e > s) ? t.substring(s, e + 1) : t;
    }

    private String buildUserPrompt(TelegramSignalContext ctx, ContextBuilder.MessageContext prior) {
        return String.format("""
                %s

                MESSAGE TEXT (Arabic + maybe English, possibly OCR-extracted from a screenshot):
                ---
                %s
                ---

                Extract the signal and return JSON only.
                """, prior.toPromptHint(), ctx.getFullText());
    }

    private String buildSystemPrompt() {
        // ⚠️ Few-shot examples مأخوذة من أنماط القناة الفعلية (SPX/SPXW Arabic).
        // غير المنطق الذهبي — فقط ساعد في classification + extraction.
        return """
                You parse Arabic SPXW option-trading signals from a Telegram channel.
                Messages may be plain text OR OCR text extracted from a screenshot of a price card.

                Return ONLY a JSON object with these fields:
                {
                  "signalType": "PREPARE | ENTRY | UPDATE | CANCEL | EXIT | PRICE_ALERT | null",
                  "symbol":   "SPXW" | "SPX" | null,
                  "optionType": "CALL" | "PUT" | null,
                  "strike":   number | null,
                  "expiryDate": "YYYY-MM-DD" | null,
                  "preparePrice":     number | null,
                  "entrySignalPrice": number | null,
                  "updatePrice":      number | null,
                  "priceAlert":       number | null,
                  "confidence":       0.0 to 1.0
                }

                ============================================
                CLASSIFICATION RULES (Arabic channel patterns):
                ============================================

                SIGNAL TYPES:

                1) PREPARE — "تجهيز / استعداد":
                   Indicators: "خليك جاهز ومراقب", "امر التنفيذ بالعقد بسعر", "استرايك : NNNN",
                              "لا تنفذ اعلى من سعر التنفيذ", "عقد CALL"/"عقد PUT".
                   Required:  strike + preparePrice + optionType.

                2) ENTRY — "دخول":
                   Indicators: "🟢 دخول CALL", "🟢 دخول PUT", "ادخل الآن", "دخول الآن".
                   Often accompanied by a live price card (e.g. "3.30 +0.80 +32%").
                   The entrySignalPrice = the LIVE price shown in the price card (the big green number).
                   IMPORTANT: ignore "وقف الخسارة 6763" and "الهدف 6827" — these are SPX index levels,
                              NOT option prices. Do NOT put them in any price field.

                3) UPDATE — "تحديث":
                   Reply to a prior PREPARE. Examples: "تحديث الأمر 3.4", "تحديث 3.5", "السعر الجديد 3.4".
                   Set updatePrice = the new number.

                4) CANCEL — "إلغاء":
                   Reply to a prior signal. Examples: "الغاء الأمر", "إلغاء", "❌ الغاء".

                5) EXIT — "خروج / بيع":
                   Reply to an ENTERED deal. Examples: "خروج", "اخرج", "بيع الآن".

                6) PRICE_ALERT:
                   Just a price card forwarded without any "دخول"/"تجهيز" caption.
                   Set priceAlert = the live price.

                ============================================
                FIELD EXTRACTION:
                ============================================

                CONTRACT HEADER (always present in screenshots):
                Format: "SPXW $6,810  26 Nov 25 (W) Call 100"
                  → symbol="SPXW", strike=6810, expiryDate="2025-11-26", optionType="CALL"

                STRIKE: from "استرايك : 6810" or from header. Numbers may use comma: "6,810" → 6810.

                PRICE FIELDS:
                  - "بسعر : 3.3" → preparePrice (in PREPARE) or entrySignalPrice (in ENTRY)
                  - Live card big number (e.g. "3.30 +0.80") → entrySignalPrice (in ENTRY) / priceAlert (otherwise)
                  - "تحديث الأمر 3.4" → updatePrice

                EXPIRY DATE:
                  - "26 Nov 25" → "2025-11-26"
                  - "بتاريخ 28 نوفمبر" → "2025-11-28" (current year if not specified)
                  - Arabic months: يناير=Jan, فبراير=Feb, مارس=Mar, ابريل=Apr, مايو=May,
                    يونيو=Jun, يوليو=Jul, اغسطس=Aug, سبتمبر=Sep, اكتوبر=Oct, نوفمبر=Nov, ديسمبر=Dec.

                ============================================
                CONTEXT USE:
                ============================================
                If "PRIOR DEAL CONTEXT" is provided, the message is a reply.
                For UPDATE / CANCEL / EXIT, you MAY copy strike/expiry/optionType from the prior context
                if not present in the new message.
                For PREPARE / ENTRY, do NOT copy from prior context — extract from current message only.

                ============================================
                CONFIDENCE:
                ============================================
                  - 1.0:  exact pattern match with all required fields.
                  - 0.7:  pattern matched but some fields inferred.
                  - 0.5:  ambiguous, possibly wrong type.
                  - 0.0:  noise / not a trading signal.

                If signalType is null OR confidence < 0.7, the system will SKIP the message.
                Output JSON only. No explanations, no markdown, no comments.
                """;
    }
}
