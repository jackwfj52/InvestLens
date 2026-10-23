package org.jack.backend.market;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.List;
import java.util.Set;

/** Bounded, versioned core-session calendar. Unsupported years never fall back to weekdays. */
@Component
public class UsEquitiesCalendar {
    public static final String ID = "US_EQUITIES_CORE", VERSION = "us-core-2026-v1";
    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-19"), LocalDate.parse("2026-02-16"),
            LocalDate.parse("2026-04-03"), LocalDate.parse("2026-05-25"), LocalDate.parse("2026-06-19"),
            LocalDate.parse("2026-07-03"), LocalDate.parse("2026-09-07"), LocalDate.parse("2026-11-26"), LocalDate.parse("2026-12-25"));
    private static final Set<LocalDate> EARLY = Set.of(LocalDate.parse("2026-11-27"), LocalDate.parse("2026-12-24"));
    public record Definition(String calendarId, String calendarVersion, int year, String timezone,
                             String regularClose, String earlyClose, int finalizationDelayMinutes,
                             List<LocalDate> holidays, List<LocalDate> earlyCloseDates, List<String> sources) { }
    public Definition definition() {
        return new Definition(ID, VERSION, 2026, ZONE.getId(), "16:00", "13:00", 15,
                HOLIDAYS.stream().sorted().toList(), EARLY.stream().sorted().toList(), List.of(
                "https://www.nyse.com/trade/hours-calendars",
                "https://www.nasdaq.com/market-activity/stock-market-holiday-schedule"));
    }
    public boolean supports(LocalDate date) { return date != null && date.getYear() == 2026; }
    public boolean isSession(LocalDate date) {
        if (!supports(date)) throw new IllegalArgumentException("Unsupported calendar year");
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(date);
    }
    public Instant close(LocalDate date) {
        if (!isSession(date)) throw new IllegalArgumentException("Not a trading session");
        return date.atTime(EARLY.contains(date) ? LocalTime.of(13, 0) : LocalTime.of(16, 0)).atZone(ZONE).toInstant();
    }
    public boolean finalized(LocalDate date, Instant observedAt) { return !close(date).plus(Duration.ofMinutes(15)).isAfter(observedAt); }
}
