package org.jack.backend.research;

import org.jack.backend.evidence.EvidenceData.Gap;
import java.util.List;

public final class ResearchData {
    private ResearchData() { }
    public record Tools(String planHash,String calculationSnapshotId,String bundleId,String scenarioId,List<Gap> gaps) {
        public Tools { gaps=List.copyOf(gaps); }
    }
    public record Frozen(tools.jackson.databind.JsonNode snapshot,tools.jackson.databind.JsonNode decision,tools.jackson.databind.JsonNode portfolio,
            tools.jackson.databind.JsonNode plan,tools.jackson.databind.JsonNode metricBundle,tools.jackson.databind.JsonNode scenarioResult,
            List<org.jack.backend.evidence.EvidenceData.Evidence> evidence,tools.jackson.databind.JsonNode instrumentMetadata,tools.jackson.databind.JsonNode inputContent) {
        public Frozen { evidence=List.copyOf(evidence); }
    }
}
