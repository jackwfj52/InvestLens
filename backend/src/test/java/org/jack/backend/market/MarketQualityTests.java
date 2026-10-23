package org.jack.backend.market;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.tool.MarketHistoryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.market.MarketData.*;
import static org.jack.backend.market.MarketQuality.*;

class MarketQualityTests {
    private final UsEquitiesCalendar calendar = new UsEquitiesCalendar();
    private final MarketQualityService service = new MarketQualityService(calendar);
    private final Instant now = Instant.parse("2026-07-08T00:00:00Z");
    private final LocalDate start = LocalDate.parse("2026-07-02"), end = LocalDate.parse("2026-07-07");
    private Instrument instrument(Adjustment adjustment) {
        return new Instrument("1.0.0", "STOCK:SYNTHETIC:QUALITY:USD", "STOCK", "QUALITY", "NASDAQ", "SYNTHETIC", "USD",
                "CURRENCY_PER_SHARE", "America/New_York", UsEquitiesCalendar.ID, UsEquitiesCalendar.VERSION, null,
                adjustment, "Fixed synthetic quality fixture; no real prices");
    }
    private Bar bar(String day, String close) { return new Bar(LocalDate.parse(day), close, close, close, close); }
    private List<Bar> good() { return List.of(bar("2026-07-02", "100"), bar("2026-07-06", "101"), bar("2026-07-07", "102")); }
    private History history(List<Bar> bars, Adjustment adjustment, boolean verified, boolean actionsVerified, List<CorporateAction> actions) {
        return new History("1.0.0", instrument(adjustment), start, end, "1d", "synthetic:fixed-quality-fixture", null,
                timestamp(now), verified, actionsVerified, new Permissions("ALLOWED", "ALLOWED", "PAYLOAD_ALLOWED"), bars, actions);
    }
    private Result assess(List<Bar> bars) { return service.assess(history(bars, Adjustment.UNADJUSTED, true, true, List.of()), now); }
    private List<String> codes(Result result) { return result.issues().stream().map(Issue::code).toList(); }
    @Test void holidayAndWeekendDoNotBecomeMissingSessions() {
        Result result = assess(good());
        assertThat(result.quality()).isEqualTo(Quality.VALID);
        assertThat(result.calculationAllowed()).isTrue();
        assertThat(result.returnBasis()).isEqualTo(ReturnBasis.PRICE_ONLY);
        assertThat(result.asOf()).isEqualTo("2026-07-07T20:00:00.000Z");
        assertThat(result.missingSessions()).isEmpty();
        assertThat(result.calculationBars()).hasSize(3);
    }
    @Test void sortsCanonicalizesAndDeduplicatesEqualValuesWithoutChangingPrices() {
        Result result = assess(List.of(bar("2026-07-07", "102.00"), bar("2026-07-02", "100.0"), bar("2026-07-06", "101"), bar("2026-07-02", "100")));
        assertThat(result.calculationAllowed()).isTrue();
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.calculationBars().stream().map(Bar::close)).containsExactly("100", "101", "102");
        assertThat(codes(result)).containsExactly("DUPLICATE_BAR");
    }
    @Test void conflictingDuplicatesDoNotPickAnArbitraryWinner() {
        Result result = assess(List.of(good().get(0), good().get(1), good().get(2), bar("2026-07-06", "90"), bar("2026-07-06", "101")));
        assertThat(result.quality()).isEqualTo(Quality.CONFLICT);
        assertThat(result.calculationAllowed()).isFalse();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.calculationBars()).isEmpty();
    }
    @Test void genuinelyMissingTradingDayIsExplicitAndNeverFilledWithZero() {
        Result result = assess(List.of(good().get(0), good().get(2)));
        assertThat(result.quality()).isEqualTo(Quality.MISSING);
        assertThat(result.missingSessions()).containsExactly(LocalDate.parse("2026-07-06"));
        assertThat(result.calculationBars()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "1e2", "0.00000000001", "1000000000000000000", "", " 100"})
    void invalidPricesBlockAllCalculationInput(String invalid) {
        Result result = assess(List.of(good().get(0), good().get(1), bar("2026-07-07", invalid)));
        assertThat(result.quality()).isEqualTo(Quality.INVALID);
        assertThat(codes(result)).contains("INVALID_PRICE_OR_OHLC");
        assertThat(result.calculationBars()).isEmpty();
    }
    @Test void invertedOhlcAndHolidayBarsAreRejected() {
        var bad = new Bar(end, "100", "101", "102", "100");
        assertThat(codes(assess(List.of(good().get(0), good().get(1), bad)))).contains("INVALID_PRICE_OR_OHLC");
        assertThat(codes(assess(List.of(good().get(0), good().get(1), good().get(2), bar("2026-07-03", "100"))))).contains("NON_TRADING_BAR");
        assertThat(codes(assess(List.of(good().get(0), good().get(1), good().get(2), bar("2026-07-08", "100"))))).contains("OUT_OF_WINDOW");
    }
    @Test void unclosedBarIsExcludedAndNotCountedAsMissing() {
        Instant intraday = Instant.parse("2026-07-07T19:59:00Z");
        History input = new History("1.0.0", instrument(Adjustment.UNADJUSTED), start, end, "1d", "synthetic:fixed", null,
                timestamp(intraday), true, true, new Permissions("ALLOWED", "ALLOWED", "PAYLOAD_ALLOWED"), good(), List.of());
        Result result = service.assess(input, intraday);
        assertThat(result.calculationAllowed()).isTrue();
        assertThat(result.unclosedCount()).isEqualTo(1);
        assertThat(result.missingSessions()).isEmpty();
        assertThat(result.asOf()).isEqualTo("2026-07-06T20:00:00.000Z");
        assertThat(result.calculationBars()).hasSize(2);
        // A newer wall clock does not turn old intraday data into a finalized bar.
        Result cached = service.assess(input, now);
        assertThat(cached.quality()).isEqualTo(Quality.STALE);
        assertThat(cached.calculationBars()).isEmpty();
    }
    @Test void unknownAdjustmentAndIncompleteCorporateActionCoverageBlock() {
        assertThat(codes(service.assess(history(good(), Adjustment.UNKNOWN, true, true, List.of()), now))).contains("ADJUSTMENT_UNKNOWN");
        assertThat(codes(service.assess(history(good(), Adjustment.UNADJUSTED, false, true, List.of()), now))).contains("ADJUSTMENT_UNKNOWN");
        assertThat(codes(service.assess(history(good(), Adjustment.UNADJUSTED, true, false, List.of()), now))).contains("CORPORATE_ACTIONS_UNVERIFIED");
    }
    @Test void splitRequiresVerifiedAdjustedPricesAndHandledAction() {
        var split = new CorporateAction(LocalDate.parse("2026-07-06"), ActionType.SPLIT, false);
        Result unhandled = service.assess(history(good(), Adjustment.UNADJUSTED, true, true, List.of(split)), now);
        assertThat(codes(unhandled)).contains("UNHANDLED_SPLIT");
        var handled = new CorporateAction(split.sessionDate(), split.type(), true);
        assertThat(codes(service.assess(history(good(), Adjustment.UNADJUSTED, true, true, List.of(handled)), now))).contains("UNHANDLED_SPLIT");
        Result adjusted = service.assess(history(good(), Adjustment.SPLIT_ADJUSTED, true, true, List.of(handled)), now);
        assertThat(adjusted.calculationAllowed()).isTrue();
        assertThat(adjusted.returnBasis()).isEqualTo(ReturnBasis.PRICE_ONLY);
    }
    @Test void dividendsNeverTurnPriceReturnsIntoTotalReturns() {
        var dividend = new CorporateAction(LocalDate.parse("2026-07-06"), ActionType.DIVIDEND, false);
        Result prices = service.assess(history(good(), Adjustment.UNADJUSTED, true, true, List.of(dividend)), now);
        assertThat(prices.calculationAllowed()).isTrue();
        assertThat(prices.returnBasis()).isEqualTo(ReturnBasis.PRICE_ONLY);
        assertThat(codes(prices)).contains("DIVIDENDS_EXCLUDED");
        assertThat(service.assess(history(good(), Adjustment.TOTAL_RETURN_ADJUSTED, true, true, List.of(dividend)), now).calculationAllowed()).isFalse();
        assertThat(service.assess(history(good(), Adjustment.TOTAL_RETURN_ADJUSTED, true, true,
                List.of(new CorporateAction(dividend.sessionDate(), dividend.type(), true))), now).returnBasis()).isEqualTo(ReturnBasis.TOTAL_RETURN);
    }
    @Test void actionOutsideDeclaredCoverageAndUnknownActionAreBlocking() {
        var outside = new CorporateAction(start.minusDays(1), ActionType.SPLIT, true);
        assertThat(codes(service.assess(history(good(), Adjustment.SPLIT_ADJUSTED, true, true, List.of(outside)), now))).contains("ACTION_COVERAGE_MISMATCH");
        var unknown = new CorporateAction(start, ActionType.OTHER, true);
        assertThat(codes(service.assess(history(good(), Adjustment.SPLIT_ADJUSTED, true, true, List.of(unknown)), now))).contains("UNHANDLED_CORPORATE_ACTION");
    }
    @Test void emptyHistoryAndUnverifiedPermissionsCannotLeakCalculationBars() {
        assertThat(assess(List.of()).quality()).isEqualTo(Quality.MISSING);
        History input = history(good(), Adjustment.UNADJUSTED, true, true, List.of());
        History blocked = new History(input.schemaVersion(), input.instrument(), start, end, "1d", input.source(), null,
                input.retrievedAt(), true, true, new Permissions("UNKNOWN", "UNKNOWN", "METADATA_ONLY"), good(), List.of());
        Result result = service.assess(blocked, now);
        assertThat(result.quality()).isEqualTo(Quality.LICENSE_BLOCKED);
        assertThat(result.calculationBars()).isEmpty();
    }
    @Test void staleAndFutureRetrievalTimesAreNotSubstitutedForMarketAsOf() {
        History input = history(good(), Adjustment.UNADJUSTED, true, true, List.of());
        for (String stamp : List.of("2026-07-06T19:00:00.000Z", "2026-07-09T00:00:00.000Z", "invalid")) {
            History changed = new History(input.schemaVersion(), input.instrument(), start, end, "1d", input.source(), null,
                    stamp, true, true, input.permissions(), good(), List.of());
            Result result = service.assess(changed, now);
            assertThat(result.calculationAllowed()).isFalse();
            assertThat(codes(result)).containsAnyOf("STALE_OBSERVATION", "INVALID_RETRIEVED_AT");
        }
    }
    @Test void calendarHandlesDstEarlyCloseAndFinalizationDelayExactly() {
        assertThat(calendar.close(LocalDate.parse("2026-01-02"))).isEqualTo(Instant.parse("2026-01-02T21:00:00Z"));
        assertThat(calendar.close(LocalDate.parse("2026-07-02"))).isEqualTo(Instant.parse("2026-07-02T20:00:00Z"));
        LocalDate early = LocalDate.parse("2026-11-27");
        assertThat(calendar.close(early)).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));
        assertThat(calendar.close(LocalDate.parse("2026-12-24"))).isEqualTo(Instant.parse("2026-12-24T18:00:00Z"));
        assertThat(calendar.finalized(early, Instant.parse("2026-11-27T18:14:59Z"))).isFalse();
        assertThat(calendar.finalized(early, Instant.parse("2026-11-27T18:15:00Z"))).isTrue();
        assertThat(calendar.isSession(LocalDate.parse("2026-04-03"))).isFalse();
        assertThat(calendar.isSession(LocalDate.parse("2026-06-19"))).isFalse();
        assertThatThrownBy(() -> calendar.isSession(LocalDate.parse("2027-01-04"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedCalendarIsStructuredAndDoesNotGuessWeekdays() {
        History input = history(good(), Adjustment.UNADJUSTED, true, true, List.of());
        History unsupported = new History(input.schemaVersion(), input.instrument(), LocalDate.parse("2027-07-02"), LocalDate.parse("2027-07-07"),
                "1d", input.source(), null, input.retrievedAt(), true, true, input.permissions(), List.of(), List.of());
        assertThat(codes(service.assess(unsupported, now))).contains("CALENDAR_UNSUPPORTED");
    }
    @Test void publicInspectionReportsQualificationGapsWithoutCallingProvider() {
        var provider = mock(MarketDataProvider.class);
        var inspection = new MarketQualityInspection(new MarketInstrumentCatalog(), provider, service, calendar);
        Result result = inspection.inspect(new Query("ETF:SINA:NYSE_ARCA:GLD:USD", start, end));
        assertThat(codes(result)).contains("LICENSE_BLOCKED", "MAINLAND_ACCESS_UNVERIFIED", "SOURCE_NOT_QUALIFIED", "ADJUSTMENT_UNKNOWN");
        assertThat(result.receivedCount()).isZero();
        assertThat(result.calculationBars()).isEmpty();
        verifyNoInteractions(provider);
    }
    @Test void researchToolReturnsOnlyQualityApprovedBarsAndStructuredReasons() {
        var provider = mock(MarketDataProvider.class);
        var catalog = mock(MarketInstrumentCatalog.class);
        var tool = new MarketHistoryTool(provider, catalog, service);
        Query query = new Query(instrument(Adjustment.UNADJUSTED).instrumentId(), start, end);
        when(catalog.requireResearchEligible(query.instrumentId())).thenReturn(new Candidate(instrument(Adjustment.UNADJUSTED), "synthetic:fixed", "QUALIFIED", true,
                new Permissions("ALLOWED", "ALLOWED", "PAYLOAD_ALLOWED")));
        when(provider.history(query)).thenReturn(history(good(), Adjustment.UNADJUSTED, true, true, List.of()));
        assertThat(tool.execute(query).calculationBars()).hasSize(3);
        when(provider.history(query)).thenReturn(history(good(), Adjustment.UNADJUSTED, false, false, List.of()));
        assertThatThrownBy(() -> tool.execute(query)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DATA_UNUSABLE);
            assertThat(error.getErrors().stream().map(e -> e.code())).contains("ADJUSTMENT_UNKNOWN", "CORPORATE_ACTIONS_UNVERIFIED");
        });
    }
    @Test void mismatchedProviderIdentityOrWindowCannotCrossToolBoundary() {
        var provider = mock(MarketDataProvider.class);
        var catalog = mock(MarketInstrumentCatalog.class);
        var tool = new MarketHistoryTool(provider, catalog, service);
        Query query = new Query("OTHER", start, end);
        var expected = new Candidate(instrument(Adjustment.SPLIT_ADJUSTED), "synthetic:fixed", "QUALIFIED", true,
                new Permissions("ALLOWED", "ALLOWED", "PAYLOAD_ALLOWED"));
        when(catalog.requireResearchEligible(query.instrumentId())).thenReturn(expected);
        when(provider.history(query)).thenReturn(history(good(), Adjustment.UNADJUSTED, true, true, List.of()));
        assertThatThrownBy(() -> tool.execute(query)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrors().stream().map(e -> e.code())).containsExactly("METADATA_MISMATCH"));
        assertThatThrownBy(() -> MarketWindow.requireMatching(new Query(expected.instrument().instrumentId(), start.plusDays(1), end),
                history(good(), Adjustment.SPLIT_ADJUSTED, true, true, List.of()), expected)).isInstanceOf(BusinessException.class);
    }
}
