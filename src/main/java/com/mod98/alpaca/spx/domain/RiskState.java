package com.mod98.alpaca.spx.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Singleton entity (id=1) — يحفظ حالة الـ risk management.
 *
 * Tracks:
 *   - consecutive losses counter (reset on any win)
 *   - kill switch state (auto-activated after N losses)
 *
 * Rule (v3.1): kill switch فعّال = لا entries جديدة (تتطلب إعادة تشغيل يدوية).
 *
 * Update flow:
 *   - Deal CLOSED with PnL < 0 → consecutiveLosses++
 *   - Deal CLOSED with PnL ≥ 0 → consecutiveLosses = 0
 *   - consecutiveLosses >= maxConsecutiveLosses → killSwitchActive = true
 */
@Entity
@Table(name = "risk_state")
@Getter
@Setter
public class RiskState {

    @Id
    private Integer id;   // always 1 (singleton)

    @Column(name = "consecutive_losses", nullable = false)
    private int consecutiveLosses;

    @Column(name = "kill_switch_active", nullable = false)
    private boolean killSwitchActive;

    @Column(name = "kill_switch_activated_at")
    private Instant killSwitchActivatedAt;

    @Column(name = "kill_switch_reason", length = 100)
    private String killSwitchReason;

    @Column(name = "last_deal_id")
    private Long lastDealId;

    @Column(name = "last_deal_pnl", precision = 18, scale = 4)
    private BigDecimal lastDealPnl;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist
    @PreUpdate
    void touch() {
        this.lastUpdated = Instant.now();
    }
}

