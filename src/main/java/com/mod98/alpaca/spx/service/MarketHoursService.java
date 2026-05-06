package com.mod98.alpaca.spx.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Set;

/**
 * يعرف هل السوق الأمريكي مفتوح حالياً للـ SPX/SPXW options.
 *
 * Regular Trading Hours (RTH) for SPX index options:
 *   Mon-Fri 9:30 AM - 4:00 PM ET (CBOE)
 *
 * SPXW also has Extended Hours:
 *   2:00 AM - 9:15 AM ET (Global Trading Hours)
 *   لكن في الـ bot هذا نستخدم RTH فقط (الأكثر أماناً).
 *
 * ملاحظة: هذا fallback بسيط. لإنتاجية أعلى استخدم IBKR reqContractDetails
 * → ContractDetails.tradingHours().
 */
@Slf4j
@Component
public class MarketHoursService {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalTime RTH_OPEN  = LocalTime.of(9, 30);
    private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

    // قائمة عطلات NYSE الأكثر شيوعاً (تحتاج تحديث سنوي)
    // For production, fetch from IBKR or NYSE calendar API.
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),   // New Year
            MonthDay.of(7, 4),   // Independence Day
            MonthDay.of(12, 25)  // Christmas
            // Memorial Day, Labor Day, Thanksgiving, MLK, Presidents Day, Juneteenth
            // → موضع لـ holiday calendar service
    );

    public boolean isMarketOpen() {
        return isMarketOpenAt(Instant.now());
    }

    public boolean isMarketOpenAt(Instant when) {
        ZonedDateTime ny = when.atZone(NY);
        DayOfWeek dow = ny.getDayOfWeek();

        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        if (FIXED_HOLIDAYS.contains(MonthDay.from(ny.toLocalDate()))) return false;

        LocalTime t = ny.toLocalTime();
        return !t.isBefore(RTH_OPEN) && t.isBefore(RTH_CLOSE);
    }

    /** كم دقيقة متبقية حتى الإغلاق (إذا السوق مفتوح). للـ logs. */
    public long minutesUntilClose() {
        if (!isMarketOpen()) return -1;
        ZonedDateTime now = ZonedDateTime.now(NY);
        ZonedDateTime close = now.with(RTH_CLOSE);
        return Duration.between(now, close).toMinutes();
    }
}
