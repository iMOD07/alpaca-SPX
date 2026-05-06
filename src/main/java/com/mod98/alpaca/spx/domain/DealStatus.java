package com.mod98.alpaca.spx.domain;

public enum DealStatus {
    PREPARE,            // تجهيز محفوظ، بانتظار "دخول"
    ENTRY_PENDING,      // أرسلنا BUY إلى IBKR لكن ما تأكد التنفيذ بعد
    ENTERED,            // FILLED (كلي أو جزئي مقبول)
    CANCELLED,          // ألغيناها قبل الدخول، أو IBKR رفضها
    CLOSED,             // TP/SL/Manual exit
    FAILED              // خطأ تقني — تحتاج مراجعة بشرية
}
