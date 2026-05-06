package com.mod98.alpaca.spx.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "deals")
@Getter
@Setter
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // SPXW - SPX
    private String symbol;

    // CALL - PUT
    @Column(name = "option_type")
    private String optionType;

    private BigDecimal strike;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    // PREPARE - ENTERED - CANCELLED - CLOSED
    @Enumerated(EnumType.STRING)
    private DealStatus status;

    // Setup price
    @Column(name = "prepare_price")
    private BigDecimal preparePrice;

    // Price from the third case (ENTRY)
    @Column(name = "entry_signal_price")
    private BigDecimal entrySignalPrice;

    // Actual execution price in Alpaca (documentation only)
    @Column(name = "entry_price")
    private BigDecimal entryPrice;

    // Minimum entry limit
    @Column(name = "entry_min_price")
    private BigDecimal entryMinPrice;

    // Maximum entry limit
    @Column(name = "entry_max_price")
    private BigDecimal entryMaxPrice;

    // Profit target (from signal price only)
    @Column(name = "tp_price")
    private BigDecimal tpPrice;

    // Stop loss (from signal price only)
    @Column(name = "sl_price")
    private BigDecimal slPrice;

    // Last known price
    @Column(name = "current_price")
    private BigDecimal currentPrice;

    // Preparation message number
    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "alpaca_entry_order_id")
    private String alpacaEntryOrderId;

    @Column(name = "alpaca_tp_order_id")
    private String alpacaTpOrderId;

    @Column(name = "alpaca_sl_order_id")
    private String alpacaSlOrderId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
