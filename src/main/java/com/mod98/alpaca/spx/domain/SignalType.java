package com.mod98.alpaca.spx.domain;

public enum SignalType {
    PREP,       // رسالة تجهيز من admin (تُحفظ في pending_entries)
    ENTRY,      // قرار دخول من البوت (يستهلك PREP)
    UPDATE,     // Reply: تحديث تاريخ أو strike على PREP
    CANCEL      // Reply: إلغاء PREP أو إغلاق صفقة مفتوحة
}
