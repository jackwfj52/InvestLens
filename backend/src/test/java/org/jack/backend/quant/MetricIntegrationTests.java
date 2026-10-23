package org.jack.backend.quant;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.support.CalculationIntegrationSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.quant.MetricData.*;

class MetricIntegrationTests extends CalculationIntegrationSupport {
    @Test void shortSamplesRetainRealCountsAndNeverPublishFakeVolatility() throws Exception {
        var s = snapshot(portfolio(), List.of(history(3, "PAYLOAD_ALLOWED")));
        var b = calculate(s);
        assertThat(b.runId()).isNull(); assertThat(b.snapshotHash()).isNull();
        assertThat(b.metrics()).extracting(Metric::value).containsExactly("-0.01", null, "0.1");
        assertThat(b.metrics()).extracting(Metric::sampleCount).containsExactly(3, 2, 3);
        Metric vol = b.metrics().get(1);
        assertThat(vol.status()).isEqualTo(Status.NOT_CALCULABLE);
        assertThat(vol.reason()).isEqualTo("INSUFFICIENT_SAMPLES");
        assertThat(vol.parameters().minimumSamples()).isEqualTo(30);
        assertThat(vol.parameters().annualizationFactor()).isEqualTo(252);
        assertThat(vol.formulaVersion()).isEqualTo(FinancialFormulas.VOLATILITY_VERSION);
        assertThat(b.sourceInputHash()).isEqualTo(s.inputHash());
        assertThat(b.inputHash()).isNotEqualTo(b.sourceInputHash());
        b.metrics().forEach(m -> {
            assertThat(m.inputHash()).isEqualTo(b.inputHash()); assertThat(m.unit()).isEqualTo("RATIO");
            assertThat(m.evidenceRefs()).containsExactly(s.marketInputs().get(0).evidenceRef());
            assertThat(b.metricHashes().get(m.metricId())).isEqualTo(CanonicalJson.sha256(json.valueToTree(m)));
        });
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g13-bundle.json"), json.writeValueAsString(b));
    }
    @Test void formalBoundaryRequiresThirtyReturnsRatherThanThirtyPrices() throws Exception {
        var p = portfolio();
        var shortBundle = calculate(snapshot(p, List.of(history(30, "PAYLOAD_ALLOWED"))));
        assertThat(shortBundle.metrics().get(1).status()).isEqualTo(Status.NOT_CALCULABLE);
        assertThat(shortBundle.metrics().get(1).sampleCount()).isEqualTo(29);
        var enough = calculate(snapshot(p, List.of(history(31, "PAYLOAD_ALLOWED"))));
        assertThat(enough.metrics()).allMatch(m -> m.status() == Status.CALCULABLE);
        assertThat(enough.metrics().get(1).sampleCount()).isEqualTo(30);
        assertThat(enough.metrics().get(0).value()).isEqualTo("0.3");
        assertThat(enough.metrics().get(2).value()).isEqualTo("0");
    }
    @Test void sameFrozenInputRecomputesIdenticallyWithoutFetchingAndOldResultsRemainReadable() throws Exception {
        var s = snapshot(portfolio(), List.of(history(31, "PAYLOAD_ALLOWED")));
        var first = calculate(s);
        reset(provider);
        var second = calculate(s);
        assertThat(second.inputHash()).isEqualTo(first.inputHash());
        assertThat(second.bundleId()).isNotEqualTo(first.bundleId());
        assertThat(second.metrics()).extracting(Metric::value).isEqualTo(first.metrics().stream().map(Metric::value).toList());
        assertThat(metrics.get(first.bundleId())).isEqualTo(first);
        verifyNoInteractions(provider);
    }
    @Test void configuredThresholdChangesHashAndDoesNotModifyOldMetrics() throws Exception {
        var s = snapshot(portfolio(), List.of(history(3, "PAYLOAD_ALLOWED")));
        var original = calculate(s);
        var custom = new MetricService(evidence, metricMapper, json, new CurrentOwner(), new QuantPolicy(2), metricFactory);
        var b = custom.calculate(new Create("1.0.0", s.snapshotId())); bundleIds.add(b.bundleId());
        assertThat(b.inputHash()).isNotEqualTo(original.inputHash());
        assertThat(b.metrics().get(1).value()).isEqualTo("2.2449944321");
        assertThat(metrics.get(original.bundleId()).metrics().get(1).value()).isNull();
        assertThatThrownBy(() -> new QuantPolicy(1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotCreationAndMetricQueriesWorkOverRealHttp() throws Exception {
        var p = portfolio(); var q = history(31, "PAYLOAD_ALLOWED");
        var created = send("POST", "/api/calculation-snapshots", Map.of("schemaVersion", "1.0.0", "portfolioSnapshotId", p.snapshotId(),
                "marketInputs", List.of(Map.of("instrumentId", q.instrumentId(), "start", q.start().toString(), "end", q.end().toString()))));
        assertThat(created.statusCode()).isEqualTo(201);
        var s = json.treeToValue(json.readTree(created.body()).get("data"), org.jack.backend.evidence.EvidenceData.Snapshot.class); track(s);
        var response = send("POST", "/api/metric-bundles", new Create("1.0.0", s.snapshotId()));
        assertThat(response.statusCode()).isEqualTo(201);
        var data = json.readTree(response.body()).get("data"); String id = data.get("bundleId").asString(); bundleIds.add(id);
        assertThat(response.headers().firstValue("Location")).contains("/api/metric-bundles/" + id);
        assertThat(json.readTree(send("GET", "/api/metric-bundles/" + id, null).body()).get("data")).isEqualTo(data);
        assertThat(json.readTree(send("GET", "/api/metric-policy", null).body()).get("data").get("minimumReturnSamples").asInt()).isEqualTo(30);
        assertThat(send("GET", "/api/metric-bundles/unknown", null).statusCode()).isEqualTo(404);
    }
    @Test void unretainedAndEmptyInputsAreRejectedAndDoNotSaveBundles() throws Exception {
        var p = portfolio();
        for (var s : List.of(snapshot(p, List.of(history(31, "METADATA_ONLY"))), snapshot(p, List.of()))) {
            assertThat(send("POST", "/api/metric-bundles", new Create("1.0.0", s.snapshotId())).statusCode()).isEqualTo(422);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metric_bundles WHERE calculation_snapshot_id = ?", Integer.class, s.snapshotId())).isZero();
        }
    }
    @Test void ownershipAndStrictRequestFieldsAreEnforced() throws Exception {
        var s = snapshot(portfolio(), List.of(history(31, "PAYLOAD_ALLOWED")));
        var b = calculate(s);
        jdbc.update("UPDATE metric_bundles SET owner_id = 'another' WHERE bundle_id = ?", b.bundleId());
        assertThat(send("GET", "/api/metric-bundles/" + b.bundleId(), null).statusCode()).isEqualTo(404);
        ObjectNode request = json.createObjectNode().put("schemaVersion", "1.0.0").put("calculationSnapshotId", s.snapshotId()).put("minimumSamples", 2);
        assertThat(send("POST", "/api/metric-bundles", request).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/api/metric-bundles", "{\"schemaVersion\":\"1.0.0\",\"calculationSnapshotId\":1}").statusCode()).isEqualTo(400);
        jdbc.update("UPDATE calculation_snapshots SET owner_id = 'another' WHERE snapshot_id = ?", s.snapshotId());
        assertThat(send("POST", "/api/metric-bundles", new Create("1.0.0", s.snapshotId())).statusCode()).isEqualTo(404);
    }
    @Test void valuesOutsideWirePrecisionAreUnavailableRatherThanInvalidJsonNumbers() throws Exception {
        var p = portfolio(); var q = history(31, "PAYLOAD_ALLOWED");
        var original = provider.history(q);
        var bars = new ArrayList<org.jack.backend.market.MarketData.Bar>();
        for (int i = 0; i < original.bars().size(); i++) {
            String value = i % 2 == 0 ? "0.0000000001" : "999999999999999999";
            bars.add(new org.jack.backend.market.MarketData.Bar(original.bars().get(i).sessionDate(), value, value, value, value));
        }
        when(provider.history(q)).thenReturn(new org.jack.backend.market.MarketData.History(original.schemaVersion(), original.instrument(), original.start(), original.end(),
                original.interval(), original.source(), original.asOf(), original.retrievedAt(), true, true, original.permissions(), bars, List.of()));
        var b = calculate(snapshot(p, List.of(q)));
        assertThat(b.metrics().get(1).value()).isNull();
        assertThat(b.metrics().get(1).reason()).isEqualTo("NUMERIC_RANGE");
        assertThat(b.metrics().get(1).quality()).isEqualTo(org.jack.backend.market.MarketQuality.Quality.INVALID);
    }
}
