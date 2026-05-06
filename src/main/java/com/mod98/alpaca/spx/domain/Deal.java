package com.mod98.alpaca.spx.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "deals",
        indexes = {
                @Index(name = "idx_deals_status", columnList = "status"),
                @Index(name = "idx_deals_telegram_msg", columnList = "telegram_message_id"),
                @Index(name = "idx_deals_created_at", columnList = "created_at")
        }
)
@Getter
@Setter
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version; // optimistic locking — يمنع race condition بين threads

    private String symbol;          // SPXW

    @Column(name = "option_type")
    private String optionType;      // CALL | PUT

    private BigDecimal strike;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    private DealStatus status;

    @Column(name = "prepare_price")
    private BigDecimal preparePrice;

    @Column(name = "entry_signal_price")
    private BigDecimal entrySignalPrice;

    @Column(name = "entry_price")
    private BigDecimal entryPrice;          // actual fill price

    @Column(name = "entry_min_price")
    private BigDecimal entryMinPrice;

    @Column(name = "entry_max_price")
    private BigDecimal entryMaxPrice;

    @Column(name = "tp_price")
    private BigDecimal tpPrice;

    @Column(name = "sl_price")
    private BigDecimal slPrice;

    @Column(name = "current_price")
    private BigDecimal currentPrice;

    @Column(name = "telegram_message_id", unique = true)
    private Long telegramMessageId;

    // ========= IBKR fields (not Alpaca) =========
    @Column(name = "ibkr_contract_id")
    private Integer ibkrContractId;         // conId — ضروري للخروج

    @Column(name = "ibkr_entry_order_id")
    private Integer ibkrEntryOrderId;

    @Column(name = "ibkr_tp_order_id")
    private Integer ibkrTpOrderId;

    @Column(name = "ibkr_sl_order_id")
    private Integer ibkrSlOrderId;

    @Column(name = "ibkr_exit_order_id")
    private Integer ibkrExitOrderId;

    // ========= Execution metadata =========
    @Column(name = "filled_qty")
    private Integer filledQty;

    @Column(name = "signal_received_at")
    private Instant signalReceivedAt;

    @Column(name = "order_sent_at")
    private Instant orderSentAt;

    @Column(name = "filled_at")
    private Instant filledAt;

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
