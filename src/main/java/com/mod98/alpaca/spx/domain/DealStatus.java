package com.mod98.alpaca.spx.domain;

/**
 * Lifecycle الجديد للـ Deal (بدون PREPARE):
 *
 *  ENTRY_PENDING  → ENTERED  → CLOSED
 *                 → CANCELLED
 *                 → FAILED
 *
 * Terminal states: ENTERED is NOT terminal (can transition to CLOSED).
 * Real terminals: CLOSED, CANCELLED, FAILED.
 */
public enum DealStatus {
    ENTRY_PENDING,   // أرسلنا BUY للـ broker، بانتظار fill
    ENTERED,         // filled — صفقة مفتوحة
    CLOSED,          // TP/SL/Manual exit
    CANCELLED,       // ألغيناها قبل fill أو broker رفض
    FAILED;          // خطأ تقني — مراجعة بشرية

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED || this == FAILED;
    }

    public boolean isOpen() {
        return this == ENTERED;
    }

    public boolean isPending() {
        return this == ENTRY_PENDING;
    }
}
