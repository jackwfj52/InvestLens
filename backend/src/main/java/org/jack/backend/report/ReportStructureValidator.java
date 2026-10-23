package org.jack.backend.report;

import org.jack.backend.agent.RoleReviewService;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.evidence.*;
import org.jack.backend.research.*;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.util.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class ReportStructureValidator {
    public record Validation(String status,String runId,String snapshotHash,String reportId,boolean numericValidationComplete,boolean published) { }
    private final ResearchFreezeService snapshots;
    private final ResearchMapper research;
    private final EvidenceService evidence;
    private final EvidenceMapper payloads;
    private final CurrentOwner owner;
    private final WorkflowStore store;
    private final WorkflowMapper mapper;
    private final ContractSchemas schemas;
    private final ReportCandidates candidates;
    private final ObjectMapper json;
    public ReportStructureValidator(ResearchFreezeService snapshots,ResearchMapper research,EvidenceService evidence,EvidenceMapper payloads,CurrentOwner owner,
            WorkflowStore store,WorkflowMapper mapper,ContractSchemas schemas,ReportCandidates candidates,ObjectMapper json){
        this.snapshots=snapshots;this.research=research;this.evidence=evidence;this.payloads=payloads;this.owner=owner;this.store=store;this.mapper=mapper;this.schemas=schemas;this.candidates=candidates;this.json=json;
    }
    public Validation validate(String runId,JsonNode report) {
        var run=store.get(runId);var frozen=snapshots.get(runId);schemas.requireCandidate(report);schemas.require("ResearchSnapshot",frozen.snapshot());
        var snapshot=frozen.snapshot();var refs=new FrozenReferences(frozen);
        if(!runId.equals(report.get("runId").asString()) || !Objects.equals(run.researchSnapshotId(),report.get("researchSnapshotId").asString())
                || !Objects.equals(run.snapshotHash(),report.get("snapshotHash").asString()) || !snapshot.get("snapshotHash").equals(report.get("snapshotHash"))
                || !snapshot.get("decisionRef").equals(report.get("decisionRef")) || !snapshot.get("portfolioRef").equals(report.get("portfolioRef"))
                || !snapshot.get("dataMode").equals(report.get("dataMode")) || !Objects.equals(run.dataQuality().name(),report.get("dataQuality").asString())
                || !frozen.metricBundle().equals(report.get("metricBundle")) || !Objects.equals(frozen.scenarioResult(),report.get("scenarioResult")))invalid();
        if(!candidates.minimum(frozen))invalid();
        var manifest=snapshot.get("manifest");if(!manifest.get("planHash").asString().equals(CanonicalJson.sha256(frozen.plan())))invalid();
        for(String key:List.of("engineVersion","qualityPolicyVersion","promptVersions","modelConfigVersion"))if(!report.get("versions").get(key).equals(manifest.get(key)))invalid();
        var metadata=new HashMap<String,String>();for(JsonNode item:frozen.instrumentMetadata())metadata.put(item.get("instrumentId").asString(),CanonicalJson.sha256(item));
        var instrumentIds=new HashSet<String>();for(JsonNode item:manifest.get("instruments"))if(!instrumentIds.add(item.get("instrumentId").asString()) || !Objects.equals(metadata.get(item.get("instrumentId").asString()),item.get("metadataHash").asString()))invalid();
        if(!instrumentIds.equals(metadata.keySet()))invalid();
        var boundIds=new HashSet<>(research.evidenceIds(runId));var actualIds=new HashSet<String>();
        for(var value:frozen.evidence()) {
            if(!actualIds.add(value.evidenceId()) || !value.runId().equals(runId) || !json.valueToTree(evidence.evidence(value.evidenceId())).equals(json.valueToTree(value)))invalid();
            if(value.payloadRef()!=null) {
                if(value.storagePolicy()!=EvidenceData.StoragePolicy.PAYLOAD_ALLOWED)invalid();
                String payload=payloads.payload(value.payloadRef(),owner.id());if(payload==null || !CanonicalJson.sha256(json.readTree(payload)).equals(value.payloadHash()))invalid();
            }
        }
        if(!boundIds.equals(actualIds))invalid();
        var manifestEvidence=new HashSet<String>();for(JsonNode item:manifest.get("evidence"))if(!manifestEvidence.add(item.get("evidenceId").asString()) || !boundIds.contains(item.get("evidenceId").asString()))invalid();
        if(!manifestEvidence.equals(actualIds))invalid();
        Set<String> reportEvidence=strings(report.get("evidenceRefs"));
        Set<String> allowedEvidence=new HashSet<>(refs.modelEvidence().stream().map(EvidenceData.Evidence::evidenceId).toList());if(!reportEvidence.equals(allowedEvidence))invalid();
        for(String id:reportEvidence)refs.evidence(id);
        var metricIds=new HashSet<String>();for(JsonNode metric:report.get("metricBundle").get("metrics")) {
            if(!metricIds.add(metric.get("metricId").asString()))invalid();
            boolean hashMatches=false;for(JsonNode item:manifest.get("metrics"))if(item.get("metricId").equals(metric.get("metricId")))hashMatches=item.get("contentHash").asString().equals(CanonicalJson.sha256(metric))
                    && item.get("inputHash").equals(metric.get("inputHash")) && item.get("formulaVersion").equals(metric.get("formulaVersion"));
            if(!hashMatches)invalid();
            for(JsonNode ref:metric.get("evidenceRefs"))if(!reportEvidence.contains(ref.asString()))invalid();
        }
        if(metricIds.size()!=manifest.get("metrics").size())invalid();
        var savedClaims=new HashMap<String,JsonNode>();var roles=report.get("roleResults");
        if(!roles.get("planner").asString().equals(research.successfulPlannerCalls(runId)>0?"AVAILABLE":"DISABLED"))invalid();
        for(StepKey key:List.of(StepKey.BULL,StepKey.BEAR)) {
            var step=store.step(runId,key);if(step.status()!=StepStatus.SUCCEEDED || step.outputRef()==null)invalid();
            var review=json.readValue(mapper.output(step.outputRef()),RoleReviewService.Review.class);
            if(!roles.get(key.name().toLowerCase()).asString().equals(review.status()) || !review.snapshotHash().equals(report.get("snapshotHash").asString()))invalid();
            for(JsonNode claim:review.claims())if(savedClaims.putIfAbsent(claim.get("claimId").asString(),claim)!=null)invalid();
        }
        var claims=new ArrayList<JsonNode>();var seenClaims=new HashSet<String>();
        for(JsonNode claim:report.get("claims")) {
            String id=claim.get("claimId").asString();if(!seenClaims.add(id) || !claim.equals(savedClaims.get(id)))invalid();
            refs.claim(claim,claim.get("role").asString());claims.add(claim);
        }
        if(!seenClaims.equals(savedClaims.keySet()))invalid();
        var judgeStep=store.step(runId,StepKey.JUDGE);
        if(judgeStep.status()==StepStatus.SUCCEEDED) {
            var saved=json.readTree(mapper.output(judgeStep.outputRef())).get("report");
            if(saved==null || !saved.get("roleResults").get("judge").equals(roles.get("judge")) || !saved.get("verdict").equals(report.get("verdict")))invalid();
        }
        var wrapper=json.createObjectNode();wrapper.put("schemaVersion","1.0.0");wrapper.put("runId",runId);wrapper.set("snapshotHash",snapshot.get("snapshotHash"));wrapper.set("verdict",report.get("verdict"));candidates.parseVerdict(wrapper,frozen,claims);
        var expectedHypotheses=new HashSet<String>();for(JsonNode hypothesis:frozen.decision().get("hypotheses"))expectedHypotheses.add(hypothesis.get("hypothesisId").asString());
        var seenHypotheses=new HashSet<String>();for(JsonNode assessment:report.get("hypothesisAssessments")) {
            if(!seenHypotheses.add(assessment.get("hypothesisId").asString()) || !assessment.get("supportStatus").asString().equals("UNVERIFIED"))invalid();
            for(JsonNode ref:assessment.get("evidenceRefs"))refs.evidence(ref.asString());for(JsonNode ref:assessment.get("metricRefs"))refs.metric(ref.asString());
        }
        if(!expectedHypotheses.equals(seenHypotheses))invalid();
        for(JsonNode ref:report.get("scenarioResult").get("metricRefs"))refs.metric(ref.asString());
        for(JsonNode rule:report.get("scenarioResult").get("ruleResults"))for(JsonNode ref:rule.get("metricRefs"))refs.metric(ref.asString());
        var expectedRequired=new HashSet<>(Set.of("REALLOCATION","DIRECT_PRICE_SHOCK"));if(!run.dataMode().name().equals("SYNTHETIC"))expectedRequired.add("HISTORICAL_RISK");
        if(!expectedRequired.equals(strings(report.get("scope").get("requiredAnalyses"))))invalid();
        if(report.get("status").asString().equals("COMPLETED") && (!candidates.requiredComplete(frozen) || !roles.properties().stream().allMatch(entry->entry.getValue().asString().equals("AVAILABLE"))))invalid();
        return new Validation("STRUCTURE_VALID",runId,run.snapshotHash(),report.get("reportId").asString(),false,false);
    }
    private static Set<String> strings(JsonNode values){var seen=new HashSet<String>();for(JsonNode value:values)if(!seen.add(value.asString()))invalid();return seen;}
    private static void invalid(){throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
}
