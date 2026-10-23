package org.jack.backend.quant;

import jakarta.validation.constraints.*;
import org.jack.backend.market.MarketQuality.Quality;
import org.jack.backend.portfolio.PortfolioDtos.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.jack.backend.common.validation.ContractPatterns.ID;

public final class MetricData {
    private MetricData() { }
    public enum Status { CALCULABLE, NOT_CALCULABLE }
    public record Create(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                         @NotBlank @Pattern(regexp = ID) String calculationSnapshotId) { }
    public record Parameters(Integer annualizationFactor, Integer minimumSamples,
                             ValuationBasis valuationBasis, Boolean costsIncluded) { }
    public record Window(LocalDate start, LocalDate end, String interval) { }
    public record Metric(String schemaVersion, String metricId, String name, Status status, String value,
                         String unit, String currency, String inputHash, String formulaVersion,
                         Parameters parameters, Window sampleWindow, int sampleCount, Quality quality,
                         String reason, List<String> evidenceRefs) {
        public Metric { evidenceRefs = List.copyOf(evidenceRefs); }
    }
    public record AssetMetrics(String instrumentId, List<String> metricRefs) {
        public AssetMetrics { metricRefs = List.copyOf(metricRefs); }
    }
    /** Pre-Run result: workflow binding fields stay null until the research freeze. */
    public record Bundle(String schemaVersion, String bundleId, String runId, String researchSnapshotId,
                         String snapshotHash, String calculationSnapshotId, String sourceInputHash,
                         String inputHash, String engineVersion, DataMode dataMode, String createdAt,
                         List<AssetMetrics> assets, List<Metric> metrics, Map<String, String> metricHashes) {
        public Bundle { assets = List.copyOf(assets); metrics = List.copyOf(metrics); metricHashes = Map.copyOf(metricHashes); }
    }
}
