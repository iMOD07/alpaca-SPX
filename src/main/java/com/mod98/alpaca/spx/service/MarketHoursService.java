package com.mod98.alpaca.spx.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Set;

/**
 * US Market hours for SPX/SPXW options (CBOE).
 *
 * RTH: Mon-Fri 9:30 AM - 4:00 PM ET
 *
 * Holiday calendar covers 2026-2027 — UPDATE before 2028.
 *
 * Sources verified:
 *  - NYSE official holiday calendar
 *  - CBOE matches NYSE for SPX options
 *  - Good Friday: SPX options market is CLOSED (unlike NYSE which is also closed)
 */
@Slf4j
@Component
public class MarketHoursService {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalTime RTH_OPEN  = LocalTime.of(9, 30);
    private static final LocalTime RTH_CLOSE = LocalTime.of(16, 0);

    /**
     * Full US market holidays for 2026-2027.
     * ⚠️ MUST be updated before 2028.
     *
     * NOTE: Early close days (1 PM ET) — typically day before Thanksgiving,
     * day after Thanksgiving, Christmas Eve, July 3 — are NOT handled here.
     * The bot is conservative: it treats these as full trading days but uses
     * the 4 PM cutoff in EntryHandler. For 0DTE this is fine; otherwise
     * consider adding an EARLY_CLOSE list with 13:00 cutoff.
     */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            // ============= 2026 =============
            LocalDate.of(2026, 1, 1),    // New Year's Day (Thu)
            LocalDate.of(2026, 1, 19),   // Martin Luther King Jr. Day (Mon)
            LocalDate.of(2026, 2, 16),   // Presidents' Day (Mon)
            LocalDate.of(2026, 4, 3),    // Good Friday
            LocalDate.of(2026, 5, 25),   // Memorial Day (Mon)
            LocalDate.of(2026, 6, 19),   // Juneteenth (Fri)
            LocalDate.of(2026, 7, 3),    // Independence Day observed (Fri, since Jul 4 = Sat)
            LocalDate.of(2026, 9, 7),    // Labor Day (Mon)
            LocalDate.of(2026, 11, 26),  // Thanksgiving (Thu)
            LocalDate.of(2026, 12, 25),  // Christmas Day (Fri)

            // ============= 2027 =============
            LocalDate.of(2027, 1, 1),    // New Year's Day (Fri)
            LocalDate.of(2027, 1, 18),   // Martin Luther King Jr. Day (Mon)
            LocalDate.of(2027, 2, 15),   // Presidents' Day (Mon)
            LocalDate.of(2027, 3, 26),   // Good Friday
            LocalDate.of(2027, 5, 31),   // Memorial Day (Mon)
            LocalDate.of(2027, 6, 18),   // Juneteenth observed (Fri, since Jun 19 = Sat)
            LocalDate.of(2027, 7, 5),    // Independence Day observed (Mon, since Jul 4 = Sun)
            LocalDate.of(2027, 9, 6),    // Labor Day (Mon)
            LocalDate.of(2027, 11, 25),  // Thanksgiving (Thu)
            LocalDate.of(2027, 12, 24)   // Christmas Day observed (Fri, since Dec 25 = Sat)
    );

    public boolean isMarketOpen() {
        return isMarketOpenAt(Instant.now());
    }

    public boolean isMarketOpenAt(Instant when) {
        ZonedDateTime ny = when.atZone(NY);

        // Weekend
        DayOfWeek dow = ny.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        // Holiday
        if (HOLIDAYS.contains(ny.toLocalDate())) return false;

        // RTH hours
        LocalTime t = ny.toLocalTime();
        return !t.isBefore(RTH_OPEN) && t.isBefore(RTH_CLOSE);
    }

    /** Returns true if today is a market holiday (regardless of time). */
    public boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date);
    }

    /** Minutes remaining until market close (or -1 if closed). */
    public long minutesUntilClose() {
        if (!isMarketOpen()) return -1;
        ZonedDateTime now = ZonedDateTime.now(NY);
        ZonedDateTime close = now.with(RTH_CLOSE);
        return Duration.between(now, close).toMinutes();
    }
}
