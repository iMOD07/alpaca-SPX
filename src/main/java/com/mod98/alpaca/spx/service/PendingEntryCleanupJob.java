package com.mod98.alpaca.spx.service;

import com.mod98.alpaca.spx.domain.PendingEntry;
import com.mod98.alpaca.spx.repo.PendingEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

/**
 * Scheduled cleanup للـ PREPs بعد 4 PM ET.
 *
 * Runs every 15 minutes during/after market hours.
 * Expires any PREP that's still active past 4 PM ET on its creation day.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingEntryCleanupJob {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_CLOSE_ET = LocalTime.of(16, 0);

    private final PendingEntryRepository pendingRepo;

    @Scheduled(fixedDelay = 15 * 60 * 1000)   // every 15 minutes
    @Transactional
    public void expireStalePreps() {
        ZonedDateTime nowET = ZonedDateTime.now(ET_ZONE);

        // فقط بعد market close نشتغل
        if (nowET.toLocalTime().isBefore(MARKET_CLOSE_ET)) {
            return;
        }

        List<PendingEntry> active = pendingRepo.findByConsumedAtIsNull();
        if (active.isEmpty()) return;

        ZonedDateTime todayCloseET = nowET.toLocalDate()
                .atTime(MARKET_CLOSE_ET)
                .atZone(ET_ZONE);
        Instant marketCloseInstant = todayCloseET.toInstant();

        int expired = 0;
        for (PendingEntry prep : active) {
            // PREPs اللي تم إنشاؤها قبل market close اليوم → expire
            if (prep.getCreatedAt().isBefore(marketCloseInstant)) {
                prep.markConsumed("expired_eod", null);
                pendingRepo.save(prep);
                expired++;
                log.info("⏱️ PREP expired (EOD) | prepId={} type={} strike={} createdAt={}",
                        prep.getId(), prep.getOptionType(),
                        prep.getStrike(), prep.getCreatedAt());
            }
        }

        if (expired > 0) {
            log.info("Expired {} stale PREPs at EOD", expired);
        }
    }
}
