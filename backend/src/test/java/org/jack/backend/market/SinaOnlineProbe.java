package org.jack.backend.market;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.market.MarketData.*;

/** Explicit -Dtest=SinaOnlineProbe only. Writes request metadata, never prices. */
class SinaOnlineProbe {
    @Test void probeThreeCandidateAssetsThroughJavaProcessBoundary() throws Exception {
        var json = JsonMapper.builder().build();
        var catalog = new MarketInstrumentCatalog();
        var transport = new SinaProcessTransport(System.getProperty("market.python", "python"), "node", "../scripts/market/sina_adapter.py", true);
        var provider = new SinaMarketDataProvider(transport, catalog, json);
        var results = new ArrayList<Map<String, Object>>();
        var qualityResults = new ArrayList<Map<String, Object>>();
        var quality = new MarketQualityService(new UsEquitiesCalendar());
        for (Candidate candidate : catalog.candidates()) {
            History history = provider.history(new Query(candidate.instrument().instrumentId(), LocalDate.parse("2026-06-25"), LocalDate.parse("2026-10-22")));
            assertThat(history.bars()).hasSizeGreaterThan(2);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("instrument", history.instrument()); summary.put("source", history.source());
            summary.put("retrievedAt", history.retrievedAt()); summary.put("sampleCount", history.bars().size());
            summary.put("firstSession", history.bars().stream().map(Bar::sessionDate).min(LocalDate::compareTo).orElseThrow());
            summary.put("lastSession", history.bars().stream().map(Bar::sessionDate).max(LocalDate::compareTo).orElseThrow());
            summary.put("adjustmentVerified", history.adjustmentVerified()); summary.put("corporateActionsVerified", history.corporateActionsVerified());
            summary.put("permissions", history.permissions()); results.add(summary);
            if (Boolean.getBoolean("market.quality")) {
                var inspected = quality.assess(history, java.time.Instant.now());
                assertThat(inspected.missingSessions()).isEmpty();
                assertThat(inspected.acceptedCount()).isEqualTo(history.bars().size());
                assertThat(inspected.calculationAllowed()).isFalse();
                assertThat(inspected.calculationBars()).isEmpty();
                qualityResults.add(Map.of("instrumentId", history.instrument().instrumentId(), "asOf", inspected.asOf(),
                        "retrievedAt", inspected.retrievedAt(), "acceptedCount", inspected.acceptedCount(),
                        "missingSessions", inspected.missingSessions(), "quality", inspected.quality(),
                        "calculationAllowed", inspected.calculationAllowed(), "issues", inspected.issues()));
            }
        }
        Files.writeString(Path.of("target/g09-sina-summary.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "schemaVersion", "1.0.0", "explicitProxyEnabled", false, "mainlandAccessVerified", false,
                "productionAllowlist", java.util.List.of(), "results", results)));
        if (Boolean.getBoolean("market.quality")) Files.writeString(Path.of("target/g10-sina-quality-summary.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("schemaVersion", "1.0.0", "qualityPolicyVersion", MarketQuality.VERSION,
                        "mainlandAccessVerified", false, "results", qualityResults)));
    }
}
