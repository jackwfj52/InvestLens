package org.jack.backend.scenario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.jack.backend.evidence.EvidenceData.PortfolioRef;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import org.jack.backend.quant.MetricData.Metric;
import org.jack.backend.decision.DecisionDtos.Action;
import org.jack.backend.decision.DecisionDtos.Constraints;
import java.util.List;
import java.util.Map;
import static org.jack.backend.common.validation.ContractPatterns.*;

public final class ScenarioData {
    private ScenarioData() { }
    public record Shock(@NotBlank @Pattern(regexp = ID) String instrumentId,
                        @NotBlank @Pattern(regexp = DECIMAL) String shock) { }
    public record Create(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                         @NotBlank @Pattern(regexp = ID) String decisionId, @NotNull @Positive Integer decisionVersion,
                         @NotBlank @Pattern(regexp = ID) String calculationSnapshotId,
                         @NotBlank @Pattern(regexp = ID) String portfolioSnapshotId,
                         @NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Shock> shocks) { }
    public record DecisionRef(String decisionId, int version) { }
    public record Weight(String instrumentId, String weight) { }
    public record Impact(String instrumentId, String weightBefore, String weightAfter, String shock,
                         String pnlBefore, String pnlAfter) { }
    public record Concentration(String formulaVersion, String unit, boolean includesCash, String maximumWeightBefore,
                                String maximumWeightAfter, String hhiBefore, String hhiAfter) { }
    public record Rule(String ruleId, String status, String threshold, String unit, List<String> metricRefs, String reason) {
        public Rule { metricRefs = List.copyOf(metricRefs); }
    }
    public record Result(String schemaVersion, String scenarioId, String runId, String researchSnapshotId,
                         String snapshotHash, String calculationSnapshotId, DecisionRef decisionRef,
                         PortfolioRef portfolioRef, String inputHash, String engineVersion, DataMode dataMode,
                         String createdAt, String sourceEvidenceRef, String pnlUnit, String currency,
                         Action action, Constraints constraints,
                         List<Weight> beforeWeights, List<Weight> afterWeights, List<Shock> shocks,
                         List<Impact> impacts, Concentration concentration, List<String> metricRefs, List<Metric> metrics,
                         Map<String, String> metricHashes, List<Rule> ruleResults, List<String> assumptions) {
        public Result {
            beforeWeights = List.copyOf(beforeWeights); afterWeights = List.copyOf(afterWeights);
            shocks = List.copyOf(shocks); impacts = List.copyOf(impacts); metricRefs = List.copyOf(metricRefs); metrics = List.copyOf(metrics);
            metricHashes = Map.copyOf(metricHashes); ruleResults = List.copyOf(ruleResults); assumptions = List.copyOf(assumptions);
        }
    }
}
