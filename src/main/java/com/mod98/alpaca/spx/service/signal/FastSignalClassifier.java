package com.mod98.alpaca.spx.service.signal;

import com.mod98.alpaca.spx.domain.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shape-aware rule-based classifier.
 *
 * يفصل بين caption (intent) و OCR (data):
 *   - Caption يحدد نوع الإشارة (PREPARE/ENTRY/PRICE_ALERT/...)
 *   - OCR يستخرج contract data (strike, expiry, type)
 *   - Reply context يربط بصفقة سابقة
 *
 * كل shape له قواعد تطابق صارمة لمنع false positives:
 *   - IMAGE_ONLY → PRICE_ALERT (لا ENTRY)
 *   - IMAGE_WITH_SHORT_CAPTION → ENTRY فقط لو caption يحتوي keyword
 *   - REPLY_TEXT_ONLY → CANCEL/UPDATE/EXIT only
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FastSignalClassifier {

    private final MessageShapeAnalyzer shapeAnalyzer;

    // ========= Action keywords (مطبقة فقط على caption، ليس OCR) =========
    private static final Pattern PREPARE_KEYWORDS = Pattern.compile(
            "خليك\\s*جاهز|امر\\s*التنفيذ|لا\\s*تنفذ\\s*اعلى|عقد\\s*CALL|عقد\\s*PUT|تجهيز",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ENTRY_KEYWORDS = Pattern.compile(
            "دخول\\s*CALL|دخول\\s*PUT|🟢\\s*دخول|دخول\\s+(الان|الآن)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_KEYWORDS = Pattern.compile(
            "تحديث|تعديل", Pattern.CASE_INSENSITIVE);

    private static final Pattern CANCEL_KEYWORDS = Pattern.compile(
            "الغاء|إلغاء|الغ\\s*الأمر|الغ\\s*الامر", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXIT_KEYWORDS = Pattern.compile(
            "خروج|اخرج|اخروج|بيع\\s*(الآن|الان)?", Pattern.CASE_INSENSITIVE);

    // ========= Field extractors (مطبقة على OCR/caption حسب الحاجة) =========
    private static final Pattern STRIKE_PATTERN = Pattern.compile(
            "(?:استرايك|strike)\\s*[:：]\\s*(\\d{3,5}(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:بسعر|سعر|@)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_PRICE_PATTERN = Pattern.compile(
            "(?:تحديث|تعديل)(?:\\s*(?:الأمر|الامر|السعر))?\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    /** Header الكارد: "SPXW $6,845 28 Nov 25 (W) Call 100" */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(SPXW?)\\s*\\$?(\\d[\\d,]*)\\s*(\\d{1,2})\\s*([A-Za-z]{3,9})\\s*(\\d{2,4})\\s*\\(?W?\\)?\\s*(Call|Put)",
            Pattern.CASE_INSENSITIVE);

    /** السعر الحي في الكارد ("3.30 +0.80"). */
    private static final Pattern LIVE_PRICE_PATTERN = Pattern.compile(
            "(?m)^\\s*(\\d+\\.\\d+)\\s+\\+\\d");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1),  Map.entry("january", 1),  Map.entry("يناير", 1),
            Map.entry("feb", 2),  Map.entry("february", 2), Map.entry("فبراير", 2),
            Map.entry("mar", 3),  Map.entry("march", 3),    Map.entry("مارس", 3),
            Map.entry("apr", 4),  Map.entry("april", 4),    Map.entry("ابريل", 4),
            Map.entry("may", 5),  Map.entry("مايو", 5),
            Map.entry("jun", 6),  Map.entry("june", 6),     Map.entry("يونيو", 6),
            Map.entry("jul", 7),  Map.entry("july", 7),     Map.entry("يوليو", 7),
            Map.entry("aug", 8),  Map.entry("august", 8),   Map.entry("اغسطس", 8),
            Map.entry("sep", 9),  Map.entry("september", 9),Map.entry("سبتمبر", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("اكتوبر", 10),
            Map.entry("nov", 11), Map.entry("november", 11),Map.entry("نوفمبر", 11),
            Map.entry("dec", 12), Map.entry("december", 12),Map.entry("ديسمبر", 12)
    );

    /**
     * Classify message — يفصل caption من OCR.
     *
     * @param caption        نص الـ caption (أو null)
     * @param ocrText        نص مستخرج من الصورة (أو null)
     * @param hasImage       هل الرسالة فيها صورة
     * @param messageId      Telegram message ID
     * @param replyToMessageId  ID للرسالة المُرَدّ عليها (أو null)
     */
    public ParsedSignal tryClassify(String caption, String ocrText, boolean hasImage,
                                    Long messageId, Long replyToMessageId) {
        String cap = normalize(caption);
        String ocr = normalize(ocrText);
        MessageShapeAnalyzer.Shape shape = shapeAnalyzer.analyze(hasImage, cap, ocr, replyToMessageId);

        return switch (shape) {
            case REPLY_TEXT_ONLY -> classifyReplyText(cap, messageId, replyToMessageId);
            case IMAGE_ONLY -> classifyImageOnly(ocr, messageId, replyToMessageId);
            case IMAGE_WITH_SHORT_CAPTION -> classifyImageWithAction(cap, ocr, messageId, replyToMessageId);
            case IMAGE_WITH_LONG_CAPTION -> classifyImageWithFullCaption(cap, ocr, messageId, replyToMessageId);
            case TEXT_ONLY -> classifyTextOnly(cap, messageId, replyToMessageId);
            default -> null;
        };
    }

    // ========================================================================
    // Shape-specific classifiers
    // ========================================================================

    /** Reply messages: CANCEL / UPDATE / EXIT only. لا ENTRY/PREPARE. */
    private ParsedSignal classifyReplyText(String text, Long msgId, Long replyTo) {
        if (CANCEL_KEYWORDS.matcher(text).find()) {
            log.info("FastClassifier: CANCEL detected (reply) | replyTo={}", replyTo);
            return build(SignalType.CANCEL, text, msgId, replyTo);
        }
        Matcher um = UPDATE_PRICE_PATTERN.matcher(text);
        if (um.find()) {
            ParsedSignal s = build(SignalType.UPDATE, text, msgId, replyTo);
            s.setUpdatePrice(parseBigDecimal(um.group(1)));
            log.info("FastClassifier: UPDATE detected | newPrice={} replyTo={}",
                    s.getUpdatePrice(), replyTo);
            return s;
        }
        if (EXIT_KEYWORDS.matcher(text).find()) {
            log.info("FastClassifier: EXIT detected (reply) | replyTo={}", replyTo);
            return build(SignalType.EXIT, text, msgId, replyTo);
        }
        // "تحديث" بدون price → نحتاج AI للسياق
        if (UPDATE_KEYWORDS.matcher(text).find()) {
            log.debug("UPDATE keyword without price — defer to AI");
        }
        return null;
    }

    /**
     * Image only (no caption) → PRICE_ALERT.
     * مهم: لا نصنّفها ENTRY أبداً حتى لو OCR استخرج "Call".
     */
    private ParsedSignal classifyImageOnly(String ocr, Long msgId, Long replyTo) {
        ParsedSignal s = build(SignalType.PRICE_ALERT, ocr, msgId, replyTo);
        extractContractFields(ocr, s);

        Matcher lp = LIVE_PRICE_PATTERN.matcher(ocr);
        if (lp.find()) {
            s.setPriceAlert(parseBigDecimal(lp.group(1)));
        }

        if (s.getStrike() != null && s.getPriceAlert() != null) {
            log.info("FastClassifier: PRICE_ALERT | strike={} price={} type={}",
                    s.getStrike(), s.getPriceAlert(), s.getOptionType());
            return s;
        }
        log.debug("Image-only without extractable price/strike — defer to AI");
        return null;
    }

    /**
     * Image + short caption (e.g. "🟢 دخول CALL").
     * Caption يحدد intent، OCR يحدد data.
     */
    private ParsedSignal classifyImageWithAction(String caption, String ocr,
                                                 Long msgId, Long replyTo) {
        // Caption هي المصدر الوحيد للـ intent — لا نطابق على OCR
        if (ENTRY_KEYWORDS.matcher(caption).find()) {
            ParsedSignal s = build(SignalType.ENTRY, caption + "\n" + ocr, msgId, replyTo);
            extractContractFields(ocr, s);   // contract data من الصورة
            Matcher lp = LIVE_PRICE_PATTERN.matcher(ocr);
            if (lp.find()) s.setEntrySignalPrice(parseBigDecimal(lp.group(1)));
            log.info("FastClassifier: ENTRY (caption-driven) | strike={} entryPrice={} type={}",
                    s.getStrike(), s.getEntrySignalPrice(), s.getOptionType());
            return s;
        }
        if (PREPARE_KEYWORDS.matcher(caption).find()) {
            return buildPrepare(caption, ocr, msgId, replyTo);
        }
        log.debug("Image with short caption but no recognized action keyword — defer to AI");
        return null;
    }

    /** Image + long caption (full PREPARE instructions). */
    private ParsedSignal classifyImageWithFullCaption(String caption, String ocr,
                                                      Long msgId, Long replyTo) {
        // ENTRY يأخذ أولوية لو caption يحتوي "دخول" حتى لو طويل
        if (ENTRY_KEYWORDS.matcher(caption).find()) {
            ParsedSignal s = build(SignalType.ENTRY, caption + "\n" + ocr, msgId, replyTo);
            extractContractFields(ocr, s);
            Matcher lp = LIVE_PRICE_PATTERN.matcher(ocr);
            if (lp.find()) s.setEntrySignalPrice(parseBigDecimal(lp.group(1)));
            log.info("FastClassifier: ENTRY (long caption) | strike={} entryPrice={}",
                    s.getStrike(), s.getEntrySignalPrice());
            return s;
        }
        if (PREPARE_KEYWORDS.matcher(caption).find()) {
            return buildPrepare(caption, ocr, msgId, replyTo);
        }
        return null;
    }

    /** Text-only message — try matching all action types. */
    private ParsedSignal classifyTextOnly(String text, Long msgId, Long replyTo) {
        if (CANCEL_KEYWORDS.matcher(text).find()) {
            return build(SignalType.CANCEL, text, msgId, replyTo);
        }
        Matcher um = UPDATE_PRICE_PATTERN.matcher(text);
        if (um.find()) {
            ParsedSignal s = build(SignalType.UPDATE, text, msgId, replyTo);
            s.setUpdatePrice(parseBigDecimal(um.group(1)));
            return s;
        }
        if (EXIT_KEYWORDS.matcher(text).find()) {
            return build(SignalType.EXIT, text, msgId, replyTo);
        }
        if (ENTRY_KEYWORDS.matcher(text).find()) {
            ParsedSignal s = build(SignalType.ENTRY, text, msgId, replyTo);
            extractContractFields(text, s);
            return (s.getStrike() != null) ? s : null;
        }
        if (PREPARE_KEYWORDS.matcher(text).find()) {
            return buildPrepare(text, "", msgId, replyTo);
        }
        return null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ParsedSignal buildPrepare(String caption, String ocr, Long msgId, Long replyTo) {
        ParsedSignal s = build(SignalType.PREPARE, caption + "\n" + ocr, msgId, replyTo);
        // contract data من OCR أولاً (الكارد header)، ثم caption احتياطياً
        extractContractFields(ocr.isBlank() ? caption : ocr, s);
        if (s.getStrike() == null) extractContractFields(caption, s);

        // Price من caption ("بسعر : 3.3") — Authoritative
        Matcher pp = PRICE_PATTERN.matcher(caption);
        if (pp.find()) {
            BigDecimal price = parseBigDecimal(pp.group(1));
            s.setPreparePrice(price);
            s.setEntrySignalPrice(price);
        }
        // Fallback: price من OCR
        if (s.getPreparePrice() == null) {
            Matcher pOcr = PRICE_PATTERN.matcher(ocr);
            if (pOcr.find()) {
                BigDecimal price = parseBigDecimal(pOcr.group(1));
                s.setPreparePrice(price);
                s.setEntrySignalPrice(price);
            }
        }

        if (s.getStrike() != null && s.getPreparePrice() != null) {
            log.info("FastClassifier: PREPARE | strike={} price={} type={} expiry={}",
                    s.getStrike(), s.getPreparePrice(), s.getOptionType(), s.getExpiryDate());
            return s;
        }
        log.debug("PREPARE keyword found but missing fields (strike={} price={}) — defer to AI",
                s.getStrike(), s.getPreparePrice());
        return null;
    }

    private void extractContractFields(String text, ParsedSignal s) {
        if (text == null || text.isBlank()) return;
        Matcher hm = HEADER_PATTERN.matcher(text);
        if (hm.find()) {
            s.setSymbol(hm.group(1).toUpperCase(Locale.ROOT));
            s.setStrike(parseBigDecimal(hm.group(2).replace(",", "")));
            int day = Integer.parseInt(hm.group(3));
            String monthName = hm.group(4).toLowerCase(Locale.ROOT);
            int year = Integer.parseInt(hm.group(5));
            if (year < 100) year += 2000;
            Integer month = MONTHS.get(monthName);
            if (month != null) {
                try { s.setExpiryDate(LocalDate.of(year, month, day)); } catch (Exception ignored) {}
            }
            s.setOptionType(hm.group(6).toUpperCase(Locale.ROOT));
        }
        if (s.getStrike() == null) {
            Matcher sm = STRIKE_PATTERN.matcher(text);
            if (sm.find()) s.setStrike(parseBigDecimal(sm.group(1)));
        }
    }

    private ParsedSignal build(SignalType type, String raw, Long msgId, Long replyTo) {
        ParsedSignal s = new ParsedSignal();
        s.setSignalType(type);
        s.setRawText(raw);
        s.setTelegramMessageId(msgId);
        s.setReplyToMessageId(replyTo);
        return s;
    }

    private BigDecimal parseBigDecimal(String x) {
        try { return new BigDecimal(x); } catch (Exception e) { return null; }
    }

    /** Normalize Arabic-Indic digits → Western. */
    private String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        return sb.toString().replaceAll("[ \\t]+", " ").trim();
    }
}
