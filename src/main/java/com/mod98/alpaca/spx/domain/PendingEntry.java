package com.mod98.alpaca.spx.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * يخزّن رسالة PREP من admin حتى يصلها ENTRY المطابق.
 *
 * Lifecycle:
 *   1. PREP يصل → INSERT (consumed_at = null)
 *   2. ENTRY يصل بنفس option_type → consumed_at = now, reason='matched_entry'
 *   3. Reply "إلغاء" يصل → consumed_at = now, reason='admin_cancel'
 *   4. End of trading day → consumed_at = now, reason='expired_eod'
 *
 * Active query:
 *   WHERE option_type = ? AND consumed_at IS NULL
 *   AND created_at >= start_of_today_et
 *   ORDER BY created_at DESC LIMIT 1
 */
@Entity
@Table(name = "pending_entries", indexes = {
        @Index(name = "idx_pending_telegram_msg", columnList = "telegram_message_id", unique = true)
})
@Getter
@Setter
public class PendingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_message_id", nullable = false, unique = true)
    private Long telegramMessageId;

    @Column(name = "option_type", nullable = false, length = 10)
    private String optionType;          // "CALL" | "PUT"

    @Column(name = "strike", nullable = false, precision = 18, scale = 4)
    private BigDecimal strike;

    @Column(name = "entry_price", nullable = false, precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "raw_text", columnDefinition = "TEXT")
    private String rawText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "consumed_by_deal_id")
    private Long consumedByDealId;

    @Column(name = "consumed_reason", length = 50)
    private String consumedReason;      // 'matched_entry' | 'admin_cancel' | 'expired_eod'

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isActive() {
        return consumedAt == null;
    }

    public void markConsumed(String reason, Long dealId) {
        this.consumedAt = Instant.now();
        this.consumedReason = reason;
        this.consumedByDealId = dealId;
    }
}
