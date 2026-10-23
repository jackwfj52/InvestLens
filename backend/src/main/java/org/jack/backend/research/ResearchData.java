package org.jack.backend.research;

import org.jack.backend.evidence.EvidenceData.Gap;
import java.util.List;

public final class ResearchData {
    private ResearchData() { }
    public record Tools(String planHash,String calculationSnapshotId,String bundleId,String scenarioId,List<Gap> gaps) {
        public Tools { gaps=List.copyOf(gaps); }
    }
}
