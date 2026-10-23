package org.jack.backend.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Provider-neutral values. Prices are canonical decimal strings, not doubles. */
public final class MarketData {
    private MarketData() { }
    public enum Adjustment { UNADJUSTED, SPLIT_ADJUSTED, TOTAL_RETURN_ADJUSTED, UNKNOWN }
    public record Instrument(String schemaVersion, String instrumentId, String instrumentType, String symbol,
                             String exchange, String provider, String quoteCurrency, String priceUnit,
                             String exchangeTimezone, String calendarId, String calendarVersion, String proxyFor,
                             Adjustment adjustment, String adjustmentDescription) { }
    public record Permissions(String localDisplay, String modelInput, String storagePolicy) { }
    public record Candidate(Instrument instrument, String metadataSource, String qualification,
                            boolean mainlandAccessVerified, Permissions permissions) { }
    public record Query(String instrumentId, LocalDate start, LocalDate end) { }
    public record Bar(LocalDate sessionDate, String open, String high, String low, String close) { }
    public record History(String schemaVersion, Instrument instrument, LocalDate start, LocalDate end,
                          String interval, String source, String asOf, String retrievedAt,
                          boolean adjustmentVerified, boolean corporateActionsVerified, Permissions permissions,
                          List<Bar> bars) {
        public History { bars = List.copyOf(bars); }
    }
    public static String timestamp(Instant value) {
        return new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }
}
