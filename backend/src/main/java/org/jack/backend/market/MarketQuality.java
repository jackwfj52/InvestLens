package org.jack.backend.market;

import java.time.LocalDate;
import java.util.List;
import static org.jack.backend.market.MarketData.*;

public final class MarketQuality {
    private MarketQuality() { }
    public static final String VERSION = "market-quality-v1";
    public enum Quality { VALID, STALE, MISSING, CONFLICT, INVALID, LICENSE_BLOCKED }
    public enum ReturnBasis { PRICE_ONLY, TOTAL_RETURN, UNKNOWN }
    public record Issue(String code, LocalDate sessionDate, boolean blocking) { }
    public record Result(String schemaVersion, String policyVersion, Quality quality, boolean calculationAllowed,
                         ReturnBasis returnBasis, Instrument instrument, LocalDate start, LocalDate end,
                         String source, String asOf, String retrievedAt, int receivedCount, int acceptedCount,
                         int unclosedCount, int duplicateCount, List<LocalDate> missingSessions, List<Issue> issues,
                         List<Bar> calculationBars) {
        public Result { missingSessions = List.copyOf(missingSessions); issues = List.copyOf(issues); calculationBars = List.copyOf(calculationBars); }
    }
}
