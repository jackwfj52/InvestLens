package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.evidence.EvidenceData;
import org.jack.backend.market.MarketQuality;
import tools.jackson.databind.JsonNode;
import java.util.*;

public final class FrozenReferences {
    private final ResearchData.Frozen frozen;
    private final Map<String,EvidenceData.Evidence> evidence=new HashMap<>();
    private final Map<String,JsonNode> metrics=new HashMap<>();
    public FrozenReferences(ResearchData.Frozen frozen) {
        this.frozen=frozen;
        frozen.evidence().forEach(value->evidence.put(value.evidenceId(),value));
        for(JsonNode value:frozen.metricBundle().get("metrics"))metrics.put(value.get("metricId").asString(),value);
    }
    public void evidence(String id) {
        var value=evidence.get(id);
        if(value==null || !value.runId().equals(frozen.snapshot().get("runId").asString()) || value.quality()!=MarketQuality.Quality.VALID
                || value.storagePolicy()!=EvidenceData.StoragePolicy.PAYLOAD_ALLOWED || value.payloadRef()==null || !value.modelInputAllowed())invalid();
        boolean exists=false;
        for(JsonNode item:frozen.snapshot().get("manifest").get("evidence"))if(item.get("evidenceId").asString().equals(id)) {
            exists=Objects.equals(item.get("payloadHash").asString(),value.payloadHash()) && item.get("storagePolicy").asString().equals(value.storagePolicy().name());
        }
        if(!exists)invalid();
    }
    public void metric(String id) {
        var metric=metrics.get(id);if(metric==null || !metric.get("status").asString().equals("CALCULABLE"))invalid();
        boolean exists=false;
        for(JsonNode item:frozen.snapshot().get("manifest").get("metrics"))if(item.get("metricId").asString().equals(id))exists=item.get("contentHash").asString().equals(CanonicalJson.sha256(metric));
        if(!exists)invalid();for(JsonNode ref:metric.get("evidenceRefs"))evidence(ref.asString());
    }
    public void claim(JsonNode claim,String role) {
        if(!claim.get("runId").asString().equals(frozen.snapshot().get("runId").asString()) || !claim.get("snapshotHash").asString().equals(frozen.snapshot().get("snapshotHash").asString())
                || !claim.get("role").asString().equals(role))invalid();
        boolean hypothesis=false;for(JsonNode item:frozen.decision().get("hypotheses"))hypothesis|=item.get("hypothesisId").equals(claim.get("hypothesisId"));if(!hypothesis)invalid();
        for(JsonNode ref:claim.get("evidenceRefs"))evidence(ref.asString());for(JsonNode ref:claim.get("metricRefs"))metric(ref.asString());
        if(claim.get("evidenceRefs").isEmpty() && claim.get("metricRefs").isEmpty() && claim.get("conditions").isEmpty() && claim.get("unknowns").isEmpty())invalid();
    }
    public List<EvidenceData.Evidence> modelEvidence(){return frozen.evidence().stream().filter(value->value.modelInputAllowed() && value.quality()==MarketQuality.Quality.VALID && value.payloadRef()!=null && value.storagePolicy()==EvidenceData.StoragePolicy.PAYLOAD_ALLOWED).toList();}
    public JsonNode modelMetrics() {
        var approved=tools.jackson.databind.json.JsonMapper.builder().build().createArrayNode();
        for(JsonNode metric:metrics.values().stream().sorted(Comparator.comparing(value->value.get("metricId").asString())).toList())try{metric(metric.get("metricId").asString());approved.add(metric);}catch(BusinessException unusable){}
        return approved;
    }
    private static void invalid(){throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
}
