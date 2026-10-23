package org.jack.backend.evidence;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.jack.backend.common.exception.*;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.market.MarketData.*;
import static org.jack.backend.portfolio.PortfolioDtos.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(EvidenceIntegrationTests.FaultConfiguration.class)
class EvidenceIntegrationTests {
    @Autowired EvidenceService service;
    @Autowired PortfolioService portfolios;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SnapshotFault fault;
    @Autowired EvidenceMapper evidenceMapper;
    @MockitoBean MarketInstrumentCatalog catalog;
    @MockitoBean MarketDataProvider provider;
    @LocalServerPort int port;
    final List<String> portfolioIds = new ArrayList<>();
    final List<String> evidenceIds = new ArrayList<>();
    final List<String> snapshotIds = new ArrayList<>();
    final String id = "ETF:SYNTHETIC:DEMO_GOLD:USD";
    final LocalDate start = LocalDate.parse("2026-07-06"), end = LocalDate.parse("2026-07-08");
    final Query query = new Query(id, start, end);

    @BeforeEach void setup() { setupPolicy("PAYLOAD_ALLOWED"); }
    void setupPolicy(String policy) {
        var instrument = new Instrument("1.0.0", id, "ETF", "DEMO_GOLD", "NYSE_ARCA", "SYNTHETIC", "USD",
                "CURRENCY_PER_SHARE", "America/New_York", UsEquitiesCalendar.ID, UsEquitiesCalendar.VERSION,
                "GOLD", Adjustment.UNADJUSTED, "Synthetic fixture only");
        var candidate = new Candidate(instrument, null, "QUALIFIED", true, new Permissions("ALLOWED", "ALLOWED", policy));
        when(catalog.requireCandidate(id)).thenReturn(candidate);
        when(provider.history(query)).thenReturn(history(instrument, candidate.permissions(), bars(), "2026-07-09T00:00:00.000Z"));
    }
    List<Bar> bars() { return List.of(bar(start, "100"), bar(start.plusDays(1), "110"), bar(end, "99")); }
    Bar bar(LocalDate day, String price) { return new Bar(day, price, price, price, price); }
    History history(Instrument instrument, Permissions permissions, List<Bar> bars, String retrieved) {
        return new History("1.0.0", instrument, start, end, "1d",
                "https://user:secret@example.com/private-secret?token=secret#secret", null, retrieved,
                true, true, permissions, bars, List.of());
    }
    PortfolioDtos.Snapshot portfolio() throws Exception {
        var value = portfolios.create(json.readValue(getClass().getResourceAsStream("/portfolio-create.json"), Create.class));
        portfolioIds.add(value.portfolioId());
        return value;
    }
    EvidenceData.Snapshot freeze(PortfolioDtos.Snapshot portfolio) {
        var value = service.freeze(portfolio.snapshotId(), List.of(query), DataMode.SYNTHETIC);
        snapshotIds.add(value.snapshotId());
        value.marketInputs().forEach(i -> evidenceIds.add(i.evidenceRef()));
        return value;
    }
    @AfterEach void cleanup() {
        fault.fail.set(false);
        snapshotIds.forEach(s -> jdbc.update("DELETE FROM calculation_snapshots WHERE snapshot_id = ?", s));
        evidenceIds.forEach(e -> {
            jdbc.update("DELETE FROM evidence_payloads WHERE evidence_id = ?", e);
            jdbc.update("DELETE FROM evidence WHERE evidence_id = ?", e);
        });
        portfolioIds.forEach(p -> {
            jdbc.update("DELETE FROM portfolio_snapshots WHERE portfolio_id = ?", p);
            jdbc.update("DELETE FROM portfolios WHERE portfolio_id = ?", p);
        });
    }

    @Test void stableNormalizedHashAndFrozenPortfolioSurviveRevisionAndProviderChanges() throws Exception {
        var p = portfolio();
        var first = freeze(p);
        // Independently reproduced with Python UTF-8 sorted-key JSON + hashlib.sha256.
        assertThat(first.inputHash()).isEqualTo("sha256:589a6c77a5475841e9a2918157d4b64ca425fed42457c205e572e0df916b8a9e");
        assertThat(first.marketInputs().get(0).inputHash()).isEqualTo("sha256:d2e21a72404a74d776ec5b3657b80262ce2cb15d8dfd0e56dc237be2fa0912ea");
        var candidate = catalog.requireCandidate(id);
        when(provider.history(query)).thenReturn(history(candidate.instrument(), candidate.permissions(),
                List.of(bar(end, "99.00"), bar(start.plusDays(1), "110.0"), bar(start, "100.00"), bar(start, "100")),
                "2026-07-10T00:00:00.000Z"));
        var second = freeze(p);
        assertThat(second.inputHash()).isEqualTo(first.inputHash()).startsWith("sha256:");
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(second.marketInputs().get(0).inputHash()).isEqualTo(first.marketInputs().get(0).inputHash());
        portfolios.revise(p.portfolioId(), new Revision("1.0.0", 1, p.positions(), "2026-10-23T01:00:00.000Z"));
        reset(provider);
        var replay = service.replay(first.snapshotId());
        assertThat(replay.portfolio()).isEqualTo(p);
        assertThat(replay.marketInputs().get(0).bars().stream().map(Bar::close)).containsExactly("100", "110", "99");
        verifyNoInteractions(provider);
        assertThat(service.evidence(first.marketInputs().get(0).evidenceRef()).sourceUrl()).isEqualTo("https://example.com");
        assertThat(get("/api/calculation-snapshots/" + first.snapshotId()).statusCode()).isEqualTo(200);
        assertThat(get("/api/calculation-snapshots/" + first.snapshotId() + "/inputs").body()).contains(first.inputHash()).doesNotContain("secret");
        assertThat(get("/api/evidence/" + first.marketInputs().get(0).evidenceRef()).statusCode()).isEqualTo(200);
    }

    @Test void changedPricesAndPortfolioContentChangeHash() throws Exception {
        var p = portfolio();
        var first = freeze(p);
        var c = catalog.requireCandidate(id);
        when(provider.history(query)).thenReturn(history(c.instrument(), c.permissions(),
                List.of(bar(start, "100"), bar(start.plusDays(1), "110"), bar(end, "98")), "2026-07-09T00:00:00.000Z"));
        assertThat(freeze(p).inputHash()).isNotEqualTo(first.inputHash());
        var revised = portfolios.revise(p.portfolioId(), new Revision("1.0.0", 1, p.positions(), "2026-10-23T01:00:00.000Z"));
        assertThat(freeze(revised).inputHash()).isNotEqualTo(first.inputHash());
    }

    @Test void metadataOnlyNeverWritesPricesOrTheirHashAndCannotReplay() throws Exception {
        setupPolicy("METADATA_ONLY");
        var s = freeze(portfolio());
        assertThat(s.replayable()).isFalse();
        assertThat(s.inputHash()).isNull();
        var e = service.evidence(s.marketInputs().get(0).evidenceRef());
        assertThat(e.payloadRef()).isNull(); assertThat(e.payloadHash()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_payloads WHERE evidence_id = ?", Integer.class, e.evidenceId())).isZero();
        assertThat(jdbc.queryForObject("SELECT CAST(content_json AS CHAR) FROM evidence WHERE evidence_id = ?", String.class, e.evidenceId()))
                .doesNotContain("110", "\"bars\"", "\"close\"", "secret");
        assertThatThrownBy(() -> service.replay(s.snapshotId())).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DATA_UNUSABLE));
        assertThat(get("/api/calculation-snapshots/" + s.snapshotId() + "/inputs").statusCode()).isEqualTo(422);
    }

    @ParameterizedTest @ValueSource(strings = {"NO_STORE", "UNKNOWN", ""})
    void forbiddenOrUnknownPolicyStoresOnlyGapAndDoesNotFetch(String policy) throws Exception {
        setupPolicy(policy);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class);
        var s = freeze(portfolio());
        assertThat(s.marketInputs()).isEmpty(); assertThat(s.inputHash()).isNull();
        assertThat(s.gaps()).extracting(EvidenceData.Gap::code).containsExactly("NO_STORE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isEqualTo(count);
        verifyNoInteractions(provider);
    }

    @Test void missingAndStaleRetainQualityWithoutCalculablePayload() throws Exception {
        var p = portfolio(); var c = catalog.requireCandidate(id);
        when(provider.history(query)).thenReturn(history(c.instrument(), c.permissions(),
                List.of(bar(start, "100"), bar(end, "99")), "2026-07-09T00:00:00.000Z"));
        var missing = freeze(p);
        var e = service.evidence(missing.marketInputs().get(0).evidenceRef());
        assertThat(e.quality()).isEqualTo(MarketQuality.Quality.MISSING);
        assertThat(e.missingSessions()).containsExactly(start.plusDays(1));
        assertThat(e.payloadHash()).isNull(); assertThat(e.modelInputAllowed()).isFalse();
        when(provider.history(query)).thenReturn(history(c.instrument(), c.permissions(), bars(), "2026-07-07T20:16:00.000Z"));
        var stale = freeze(p);
        assertThat(service.evidence(stale.marketInputs().get(0).evidenceRef()).quality()).isEqualTo(MarketQuality.Quality.STALE);
        assertThat(stale.replayable()).isFalse();
    }

    @Test void snapshotFailureRollsBackEvidenceAndPayload() throws Exception {
        var p = portfolio();
        int evidence = jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class);
        int payloads = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_payloads", Integer.class);
        fault.fail.set(true);
        assertThatThrownBy(() -> freeze(p)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isEqualTo(evidence);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_payloads", Integer.class)).isEqualTo(payloads);
    }

    @Test void ownerIsolationAndPayloadIntegrityAreEnforced() throws Exception {
        var s = freeze(portfolio()); String e = s.marketInputs().get(0).evidenceRef();
        jdbc.update("UPDATE evidence_payloads SET content_json = JSON_SET(content_json, '$.bars[0].close', '500') WHERE evidence_id = ?", e);
        assertThatThrownBy(() -> service.replay(s.snapshotId())).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_MISMATCH));
        jdbc.update("UPDATE calculation_snapshots SET owner_id = 'another' WHERE snapshot_id = ?", s.snapshotId());
        jdbc.update("UPDATE evidence SET owner_id = 'another' WHERE evidence_id = ?", e);
        assertThat(get("/api/calculation-snapshots/" + s.snapshotId()).statusCode()).isEqualTo(404);
        assertThat(get("/api/evidence/" + e).statusCode()).isEqualTo(404);
    }

    @Test void mismatchedModeAndDuplicateQueriesAreRejectedBeforeFetch() throws Exception {
        var p = portfolio();
        assertThatThrownBy(() -> service.freeze(p.snapshotId(), List.of(query), DataMode.LIVE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.freeze(p.snapshotId(), List.of(query, query), DataMode.SYNTHETIC)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(provider);
    }

    @Test void databaseRejectsPayloadAttachedToMetadataOnlyEvidence() throws Exception {
        setupPolicy("METADATA_ONLY");
        var s = freeze(portfolio());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_payloads VALUES (?, 'PAYLOAD_ALLOWED', '{}')",
                s.marketInputs().get(0).evidenceRef())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void unqualifiedProviderDoesNotFetchAndIsNotModelInput() throws Exception {
        var c = catalog.requireCandidate(id);
        when(catalog.requireCandidate(id)).thenReturn(new Candidate(c.instrument(), null, "PENDING", false,
                new Permissions("UNKNOWN", "UNKNOWN", "METADATA_ONLY")));
        var s = freeze(portfolio());
        var e = service.evidence(s.marketInputs().get(0).evidenceRef());
        assertThat(e.modelInputAllowed()).isFalse();
        assertThat(e.quality()).isEqualTo(MarketQuality.Quality.LICENSE_BLOCKED);
        assertThat(e.payloadHash()).isNull();
        verifyNoInteractions(provider);
    }

    @Test void independentServiceInstanceReadsOriginalInputsWithoutProvider() throws Exception {
        var s = freeze(portfolio());
        reset(provider);
        var other = new EvidenceService(evidenceMapper, json,
                new org.jack.backend.common.identity.CurrentOwner(), portfolios, catalog,
                org.mockito.Mockito.mock(MarketQualityInspection.class));
        assertThat(other.snapshot(s.snapshotId())).isEqualTo(s);
        assertThat(other.replay(s.snapshotId()).marketInputs()).hasSize(1);
        verifyNoInteractions(provider);
    }

    @Test void requestAssetOrderDoesNotChangeInputHash() throws Exception {
        var p = portfolio();
        var gold = catalog.requireCandidate(id);
        var i = gold.instrument();
        String silverId = "ETF:SYNTHETIC:DEMO_SILVER:USD";
        var silver = new Instrument(i.schemaVersion(), silverId, i.instrumentType(), "DEMO_SILVER", i.exchange(),
                i.provider(), i.quoteCurrency(), i.priceUnit(), i.exchangeTimezone(), i.calendarId(), i.calendarVersion(),
                "SILVER", i.adjustment(), i.adjustmentDescription());
        when(catalog.requireCandidate(silverId)).thenReturn(new Candidate(silver, null, "QUALIFIED", true, gold.permissions()));
        var silverQuery = new Query(silverId, start, end);
        when(provider.history(silverQuery)).thenReturn(history(silver, gold.permissions(), bars(), "2026-07-09T00:00:00.000Z"));
        String previousHash = null;
        for (var queries : List.of(List.of(query, silverQuery), List.of(silverQuery, query))) {
            var s = service.freeze(p.snapshotId(), queries, DataMode.SYNTHETIC);
            snapshotIds.add(s.snapshotId());
            s.marketInputs().forEach(input -> evidenceIds.add(input.evidenceRef()));
            assertThat(s.marketInputs()).extracting(EvidenceData.MarketInput::instrumentId).containsExactly(id, silverId);
            if (previousHash != null) assertThat(s.inputHash()).isEqualTo(previousHash);
            previousHash = s.inputHash();
        }
    }

    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @TestConfiguration static class FaultConfiguration {
        @Bean SnapshotFault snapshotFault() { return new SnapshotFault(); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class SnapshotFault implements Interceptor {
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement) invocation.getArgs()[0]).getId().equals(EvidenceMapper.class.getName() + ".insertSnapshot") && fail.getAndSet(false)) {
                throw new IllegalStateException("Synthetic persistence fault");
            }
            return invocation.proceed();
        }
    }
}
