package com.mod98.alpaca.spx.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * DB-backed idempotency — يصمد عبر restarts.
 *
 * Schema (Flyway migration):
 *
 *   CREATE TABLE processed_messages (
 *       message_id BIGINT PRIMARY KEY,
 *       processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
 *   );
 *   CREATE INDEX idx_processed_at ON processed_messages(processed_at);
 *
 * Cleanup: row قديمة (> 7 أيام) تُحذف عبر scheduled job (مش هنا).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;

    /**
     * يحجز messageId — يرجّع true لو هذي أول مرة.
     *
     * Implementation: INSERT ... ON CONFLICT DO NOTHING (atomic).
     * propagation=REQUIRES_NEW لأن نريد الـ commit مستقل عن transaction الخارجي.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(long messageId) {
        try {
            int rows = jdbc.update(
                    "INSERT INTO processed_messages (message_id) VALUES (?) " +
                            "ON CONFLICT (message_id) DO NOTHING",
                    messageId);
            if (rows == 0) {
                log.warn("DUPLICATE blocked | messageId={}", messageId);
                return false;
            }
            return true;
        } catch (DuplicateKeyException e) {
            // fallback لو DB engine ما يدعم ON CONFLICT (PostgreSQL يدعمها)
            log.warn("DUPLICATE blocked (catch) | messageId={}", messageId);
            return false;
        }
    }

    /**
     * تنظيف rows قديمة — يُستدعى من scheduled job.
     * يُقلل حجم الجدول ويحافظ على performance.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cleanupOlderThan(Duration age) {
        return jdbc.update(
                "DELETE FROM processed_messages WHERE processed_at < NOW() - INTERVAL '" +
                        age.toDays() + " days'");
    }
}
