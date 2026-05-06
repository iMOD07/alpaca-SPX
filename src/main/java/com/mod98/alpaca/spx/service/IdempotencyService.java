package com.mod98.alpaca.spx.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * يضمن أن نفس الـ Telegram messageId ما يتم معالجته مرتين.
 * In-memory — كافي لأن TDLib ما يعيد نفس الرسالة بعد restart.
 * لو احتجنا persistence: استخدم Redis SET NX أو DB unique constraint.
 */
@Slf4j
@Component
public class IdempotencyService {

    private static final Duration TTL = Duration.ofHours(2);
    private final Map<Long, Long> processed = new ConcurrentHashMap<>();

    /** يرجّع true لو هذي أول مرة — false لو سبق تعالجت. */
    public boolean tryAcquire(long messageId) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        Long previous = processed.putIfAbsent(messageId, now);
        if (previous != null) {
            log.warn("DUPLICATE signal blocked | messageId={} firstSeen={}", messageId, previous);
            return false;
        }
        return true;
    }

    private void evictExpired(long now) {
        long cutoff = now - TTL.toMillis();
        processed.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
