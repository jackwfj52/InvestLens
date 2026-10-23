package org.jack.backend.support;

import org.jack.backend.evidence.*;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.jack.backend.quant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.market.MarketData.*;
import static org.jack.backend.portfolio.PortfolioDtos.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
public abstract class CalculationIntegrationSupport {
    @Autowired protected EvidenceService evidence;
    @Autowired protected PortfolioService portfolios;
    @Autowired protected MetricService metrics;
    @Autowired protected MetricMapper metricMapper;
    @Autowired protected MetricFactory metricFactory;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;
    @MockitoBean protected MarketInstrumentCatalog catalog;
    @MockitoBean protected MarketDataProvider provider;
    @LocalServerPort protected int port;
    protected final List<String> portfolioIds = new ArrayList<>(), snapshotIds = new ArrayList<>(), evidenceIds = new ArrayList<>(), bundleIds = new ArrayList<>();
    protected static final String GOLD = "ETF:SYNTHETIC:DEMO_GOLD:USD";
    protected static final String SILVER = "ETF:SYNTHETIC:DEMO_SILVER:USD";
    protected static final String CASH = "CASH:USD";

    protected PortfolioDtos.Snapshot portfolio() throws Exception { return portfolio(null); }
    protected PortfolioDtos.Snapshot portfolio(String absoluteTotal) throws Exception {
        ObjectNode input = (ObjectNode) json.readTree(getClass().getResourceAsStream("/portfolio-create.json"));
        if (absoluteTotal != null) { input.put("valuationBasis", "ABSOLUTE"); input.put("totalValue", absoluteTotal); }
        var p = portfolios.create(json.treeToValue(input, Create.class));
        portfolioIds.add(p.portfolioId()); return p;
    }
    protected Query history(int count, String policy) {
        var calendar = new UsEquitiesCalendar();
        var bars = new ArrayList<Bar>();
        for (LocalDate day = LocalDate.parse("2026-06-01"); bars.size() < count; day = day.plusDays(1)) {
            if (calendar.isSession(day)) {
                String price = count == 3 ? List.of("100", "110", "99").get(bars.size()) : Integer.toString(100 + bars.size());
                bars.add(new Bar(day, price, price, price, price));
            }
        }
        var i = new Instrument("1.0.0", GOLD, "ETF", "DEMO_GOLD", "NYSE_ARCA", "SYNTHETIC", "USD",
                "CURRENCY_PER_SHARE", "America/New_York", UsEquitiesCalendar.ID, UsEquitiesCalendar.VERSION,
                "GOLD", Adjustment.UNADJUSTED, "Synthetic metric integration fixture");
        var permissions = new Permissions("ALLOWED", "ALLOWED", policy);
        var query = new Query(GOLD, bars.get(0).sessionDate(), bars.get(bars.size() - 1).sessionDate());
        when(catalog.requireCandidate(GOLD)).thenReturn(new Candidate(i, null, "QUALIFIED", true, permissions));
        when(provider.history(query)).thenReturn(new History("1.0.0", i, query.start(), query.end(), "1d",
                "https://example.com/synthetic", null, "2026-10-23T00:00:00.000Z", true, true, permissions, bars, List.of()));
        return query;
    }
    protected EvidenceData.Snapshot snapshot(PortfolioDtos.Snapshot p, List<Query> queries) {
        var s = evidence.freeze(p.snapshotId(), queries, p.dataMode()); track(s); return s;
    }
    protected void track(EvidenceData.Snapshot s) {
        snapshotIds.add(s.snapshotId()); s.marketInputs().forEach(i -> evidenceIds.add(i.evidenceRef()));
    }
    protected MetricData.Bundle calculate(EvidenceData.Snapshot s) {
        var b = metrics.calculate(new MetricData.Create("1.0.0", s.snapshotId())); bundleIds.add(b.bundleId()); return b;
    }
    protected HttpResponse<String> send(String method, String path, Object body) throws Exception {
        String payload = body == null ? null : body instanceof String text ? text : json.writeValueAsString(body);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @AfterEach protected void cleanCalculationRows() {
        bundleIds.forEach(id -> jdbc.update("DELETE FROM metric_bundles WHERE bundle_id = ?", id));
        snapshotIds.forEach(id -> jdbc.update("DELETE FROM calculation_snapshots WHERE snapshot_id = ?", id));
        evidenceIds.forEach(id -> { jdbc.update("DELETE FROM evidence_payloads WHERE evidence_id = ?", id); jdbc.update("DELETE FROM evidence WHERE evidence_id = ?", id); });
        portfolioIds.forEach(id -> { jdbc.update("DELETE FROM portfolio_snapshots WHERE portfolio_id = ?", id); jdbc.update("DELETE FROM portfolios WHERE portfolio_id = ?", id); });
    }
}
