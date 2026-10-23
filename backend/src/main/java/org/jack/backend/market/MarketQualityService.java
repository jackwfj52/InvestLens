package org.jack.backend.market;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.jack.backend.market.MarketData.*;
import static org.jack.backend.market.MarketQuality.*;

@Component
public class MarketQualityService {
    private static final Pattern PRICE = Pattern.compile("-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,10})?");
    private final UsEquitiesCalendar calendar;
    public MarketQualityService(UsEquitiesCalendar calendar) { this.calendar = calendar; }

    public Result assess(History history, Instant now) {
        var issues = new ArrayList<Issue>();
        var accepted = new TreeMap<LocalDate, Bar>();
        var seen = new HashSet<LocalDate>();
        var conflicts = new HashSet<LocalDate>();
        var missing = new ArrayList<LocalDate>();
        Instrument instrument = history.instrument();
        int unclosed = 0, duplicate = 0;
        if (!"ALLOWED".equals(history.permissions().localDisplay()) || !"ALLOWED".equals(history.permissions().modelInput())) {
            issues.add(new Issue("LICENSE_BLOCKED", null, true));
        }
        boolean validCalendar = UsEquitiesCalendar.ID.equals(instrument.calendarId())
                && UsEquitiesCalendar.VERSION.equals(instrument.calendarVersion())
                && UsEquitiesCalendar.ZONE.getId().equals(instrument.exchangeTimezone())
                && calendar.supports(history.start()) && calendar.supports(history.end()) && !history.end().isBefore(history.start())
                && java.time.temporal.ChronoUnit.DAYS.between(history.start(), history.end()) <= 119;
        if (!validCalendar) issues.add(new Issue("CALENDAR_UNSUPPORTED", null, true));
        if (!"USD".equals(instrument.quoteCurrency()) || !"CURRENCY_PER_SHARE".equals(instrument.priceUnit())
                || !("STOCK".equals(instrument.instrumentType()) || "ETF".equals(instrument.instrumentType()))
                || !("NASDAQ".equals(instrument.exchange()) || "NYSE_ARCA".equals(instrument.exchange())) || !"1d".equals(history.interval())) {
            issues.add(new Issue("METADATA_MISMATCH", null, true));
        }
        Instant retrieved;
        try {
            retrieved = Instant.parse(history.retrievedAt());
            if (!timestamp(retrieved).equals(history.retrievedAt()) || retrieved.isAfter(now.plusSeconds(60))) throw new IllegalArgumentException();
        } catch (RuntimeException error) { retrieved = Instant.MIN; issues.add(new Issue("INVALID_RETRIEVED_AT", null, true)); }
        Instant cutoff = retrieved.isBefore(now) ? retrieved : now;
        for (Bar bar : history.bars()) {
            if (bar == null || bar.sessionDate() == null) { issues.add(new Issue("INVALID_BAR", null, true)); continue; }
            LocalDate day = bar.sessionDate();
            if (day.isBefore(history.start()) || day.isAfter(history.end())) { issues.add(new Issue("OUT_OF_WINDOW", day, true)); continue; }
            if (!validCalendar) continue;
            if (!calendar.isSession(day)) { issues.add(new Issue("NON_TRADING_BAR", day, true)); continue; }
            seen.add(day);
            if (!calendar.finalized(day, cutoff)) { unclosed++; issues.add(new Issue("UNCLOSED_BAR", day, false)); continue; }
            Bar normalized;
            try { normalized = normalize(bar); }
            catch (IllegalArgumentException error) { issues.add(new Issue("INVALID_PRICE_OR_OHLC", day, true)); continue; }
            if (conflicts.contains(day)) { duplicate++; continue; }
            Bar previous = accepted.putIfAbsent(day, normalized);
            if (previous != null) {
                duplicate++;
                if (!previous.equals(normalized)) {
                    conflicts.add(day); accepted.remove(day); issues.add(new Issue("CONFLICTING_DUPLICATE", day, true));
                } else issues.add(new Issue("DUPLICATE_BAR", day, false));
            }
        }
        if (validCalendar) {
            for (LocalDate day = history.start(); !day.isAfter(history.end()); day = day.plusDays(1)) {
                if (calendar.isSession(day) && calendar.finalized(day, now)) {
                    if (!calendar.finalized(day, retrieved)) issues.add(new Issue("STALE_OBSERVATION", day, true));
                    if (!seen.contains(day)) missing.add(day);
                }
            }
        }
        if (!missing.isEmpty()) issues.add(new Issue("MISSING_SESSIONS", null, true));
        if (accepted.size() < 2) issues.add(new Issue("INSUFFICIENT_SAMPLES", null, true));
        ReturnBasis basis = history.adjustmentVerified() && instrument.adjustment() != null ? switch (instrument.adjustment()) {
            case UNADJUSTED, SPLIT_ADJUSTED -> ReturnBasis.PRICE_ONLY;
            case TOTAL_RETURN_ADJUSTED -> ReturnBasis.TOTAL_RETURN;
            case UNKNOWN -> ReturnBasis.UNKNOWN;
        } : ReturnBasis.UNKNOWN;
        if (basis == ReturnBasis.UNKNOWN) issues.add(new Issue("ADJUSTMENT_UNKNOWN", null, true));
        if (!history.corporateActionsVerified()) issues.add(new Issue("CORPORATE_ACTIONS_UNVERIFIED", null, true));
        for (CorporateAction action : history.corporateActions()) {
            if (action.type() == null) { issues.add(new Issue("INVALID_CORPORATE_ACTION", action.sessionDate(), true)); continue; }
            if (action.sessionDate() == null || action.sessionDate().isBefore(history.start()) || action.sessionDate().isAfter(history.end())) {
                issues.add(new Issue("ACTION_COVERAGE_MISMATCH", action.sessionDate(), true)); continue;
            }
            if (action.type() == ActionType.SPLIT && (!action.handled() || instrument.adjustment() == Adjustment.UNADJUSTED)) {
                issues.add(new Issue("UNHANDLED_SPLIT", action.sessionDate(), true));
            } else if (action.type() == ActionType.DIVIDEND && basis == ReturnBasis.PRICE_ONLY) {
                issues.add(new Issue("DIVIDENDS_EXCLUDED", action.sessionDate(), false));
            } else if (!action.handled() || action.type() == ActionType.OTHER) {
                issues.add(new Issue("UNHANDLED_CORPORATE_ACTION", action.sessionDate(), true));
            }
        }
        boolean allowed = issues.stream().noneMatch(Issue::blocking);
        Quality quality = classify(issues);
        String asOf = accepted.isEmpty() ? null : timestamp(calendar.close(accepted.lastKey()));
        return new Result("1.0.0", VERSION, quality, allowed, basis, instrument, history.start(), history.end(),
                history.source(), asOf, history.retrievedAt(), history.bars().size(), accepted.size(), unclosed,
                duplicate, missing, issues, allowed ? new ArrayList<>(accepted.values()) : List.of());
    }
    private static Bar normalize(Bar bar) {
        BigDecimal open = price(bar.open()), high = price(bar.high()), low = price(bar.low()), close = price(bar.close());
        if (low.compareTo(high) > 0 || low.compareTo(open.min(close)) > 0 || high.compareTo(open.max(close)) < 0) throw new IllegalArgumentException();
        return new Bar(bar.sessionDate(), decimal(open), decimal(high), decimal(low), decimal(close));
    }
    private static BigDecimal price(String value) {
        if (value == null || !PRICE.matcher(value).matches()) throw new IllegalArgumentException();
        BigDecimal price = new BigDecimal(value);
        if (price.signum() <= 0) throw new IllegalArgumentException();
        return price;
    }
    private static String decimal(BigDecimal price) { return price.stripTrailingZeros().toPlainString(); }
    private static Quality classify(List<Issue> issues) {
        if (issues.stream().anyMatch(i -> i.code().equals("LICENSE_BLOCKED"))) return Quality.LICENSE_BLOCKED;
        if (issues.stream().anyMatch(i -> i.code().equals("CONFLICTING_DUPLICATE"))) return Quality.CONFLICT;
        if (issues.stream().anyMatch(i -> i.blocking() && !Set.of("MISSING_SESSIONS", "INSUFFICIENT_SAMPLES", "STALE_OBSERVATION").contains(i.code()))) return Quality.INVALID;
        if (issues.stream().anyMatch(i -> i.code().equals("STALE_OBSERVATION"))) return Quality.STALE;
        if (issues.stream().anyMatch(Issue::blocking)) return Quality.MISSING;
        return Quality.VALID;
    }
}
