package com.mod98.alpaca.spx.service.processor;

import com.mod98.alpaca.spx.domain.PendingEntry;
import com.mod98.alpaca.spx.repo.PendingEntryRepository;
import com.mod98.alpaca.spx.service.signal.ParsedSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * يعالج رسائل CANCEL المستقلة (ليست reply):
 *
 *   "⚠️إلغاء أمر التنفيذ ⚠️
 *    🟢 عقد CALL 🟢
 *    🎯 إسترايك : 7395
 *    ❌ لم يحقق دخول بسعر الطلب"
 *
 * يبحث عن PREP بنفس option_type + strike في اليوم الحالي ويلغيها.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCancelHandler {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    private static final Pattern OPTION_TYPE = Pattern.compile(
            "عقد\\s*(CALL|PUT)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern STRIKE = Pattern.compile(
            "(?:استرايك|إسترايك)\\s*[:：]\\s*(\\d{4,5})", Pattern.UNICODE_CASE);

    private final PendingEntryRepository pendingRepo;

    @Transactional
    public void handle(ParsedSignal signal) {
        String text = signal.getRawText();
        if (text == null) return;

        // استخرج option_type + strike من نص الـ CANCEL
        Matcher tm = OPTION_TYPE.matcher(text);
        Matcher sm = STRIKE.matcher(text);
        if (!tm.find() || !sm.find()) {
            log.debug("AdminCancel ignored — type/strike missing | msgId={}",
                    signal.getTelegramMessageId());
            return;
        }

        String optType = tm.group(1).toUpperCase();
        BigDecimal strike = new BigDecimal(sm.group(1));

        // ابحث في PREPs النشطة من اليوم
        Instant startOfTodayET = ZonedDateTime.now(ET_ZONE)
                .toLocalDate()
                .atStartOfDay(ET_ZONE)
                .toInstant();

        List<PendingEntry> candidates = pendingRepo
                .findActiveByTypeOrderByNewestFirst(optType, startOfTodayET);

        // اختر اللي بنفس strike
        PendingEntry target = candidates.stream()
                .filter(p -> p.getStrike().compareTo(strike) == 0)
                .findFirst()
                .orElse(null);

        if (target == null) {
            log.debug("AdminCancel: no matching PREP found | type={} strike={}",
                    optType, strike);
            return;
        }

        target.markConsumed("admin_cancel", null);
        pendingRepo.save(target);
        log.info("❌ PREP cancelled by admin (standalone msg) | prepId={} type={} strike={}",
                target.getId(), optType, strike);
    }
}

