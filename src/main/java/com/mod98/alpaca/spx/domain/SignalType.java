package com.mod98.alpaca.spx.domain;

/**
 * أنواع الإشارات المدعومة (مبسّطة — بدون PREPARE).
 *
 * ENTRY: قرار دخول مباشر — يجب أن يحوي قرار + بيانات عقد كاملة.
 * UPDATE: تعديل على Deal موجودة (entry/SL/TP) عبر Reply.
 * CANCEL: إلغاء Deal لم تُنفّذ بعد، أو إغلاق Deal مفتوحة.
 */
public enum SignalType {
    ENTRY,
    UPDATE,
    CANCEL
}
