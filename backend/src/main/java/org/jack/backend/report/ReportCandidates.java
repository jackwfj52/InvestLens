package org.jack.backend.report;

import org.jack.backend.agent.RoleReviewService;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.evidence.EvidenceData;
import org.jack.backend.llm.StructuredModel;
import org.jack.backend.research.*;
import org.jack.backend.workflow.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class ReportCandidates {
    public record Candidate(String state,JsonNode report,List<EvidenceData.Gap> diagnostics) {
        public Candidate { diagnostics=List.copyOf(diagnostics); }
    }
    private final ResearchFreezeService snapshots;
    private final WorkflowStore store;
    private final WorkflowMapper mapper;
    private final StructuredModel model;
    private final RoleReviewService reviews;
    private final ContractSchemas schemas;
    private final ObjectMapper json;
    private final ResearchMapper research;
    public ReportCandidates(ResearchFreezeService snapshots,WorkflowStore store,WorkflowMapper mapper,StructuredModel model,RoleReviewService reviews,ContractSchemas schemas,ObjectMapper json,ResearchMapper research){
        this.snapshots=snapshots;this.store=store;this.mapper=mapper;this.model=model;this.reviews=reviews;this.schemas=schemas;this.json=json;
        this.research=research;
    }
    public Candidate assemble(WorkflowClaimService.Claim claim,boolean enabled) {
        if(claim.step().stepKey()!=StepKey.JUDGE)throw new BusinessException(ErrorCode.INVALID_STATE);
        var frozen=snapshots.get(claim.run().runId());
        var gaps=new ArrayList<EvidenceData.Gap>();
        for(JsonNode gap:frozen.snapshot().get("manifest").get("gaps"))gaps.add(json.treeToValue(gap,EvidenceData.Gap.class));
        if(!minimum(frozen)) {
            gaps.add(new EvidenceData.Gap("MINIMUM_REPORT_UNAVAILABLE",null,"缺少合法调仓或显式冲击结果，不能形成报告候选"));
            return new Candidate("FAILED",null,gaps);
        }
        var bull=readReview(claim.run().runId(),StepKey.BULL,frozen);var bear=readReview(claim.run().runId(),StepKey.BEAR,frozen);
        var all=new ArrayList<JsonNode>();var claimIds=new HashSet<String>();
        for(var review:List.of(bull,bear))if(review.status().equals("AVAILABLE"))for(JsonNode value:review.claims()) {
            if(!claimIds.add(value.get("claimId").asString()))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);all.add(value);
        }
        String judgeStatus=enabled?"MISSING":"DISABLED";JsonNode verdict=fallback();
        if(enabled)try {
            String prompt=new ClassPathResource("prompts/judge-v1.txt").getContentAsString(StandardCharsets.UTF_8);
            var refs=new FrozenReferences(frozen);
            String input=json.writeValueAsString(Map.of("runId",claim.run().runId(),"snapshotHash",frozen.snapshot().get("snapshotHash").asString(),"claims",all,
                    "metrics",refs.modelMetrics(),"evidence",refs.modelEvidence(),"roleResults",List.of(bull.status(),bear.status()),"gaps",gaps));
            verdict=model.complete(claim,prompt,input,value->parseVerdict(value,frozen,all));judgeStatus="AVAILABLE";
        }catch(java.io.IOException missing){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
        catch(BusinessException failure) {
            if(failure.getErrorCode()==ErrorCode.VERSION_CONFLICT)throw failure;
            gaps.add(new EvidenceData.Gap("JUDGE_"+failure.getErrorCode().name(),null,"Judge 不可用，采用确定性不足证据结论"));
        }
        var roleResults=Map.of("planner",research.successfulPlannerCalls(claim.run().runId())>0?"AVAILABLE":"DISABLED","bull",bull.status(),"bear",bear.status(),"judge",judgeStatus);
        for(var entry:roleResults.entrySet())if(!entry.getValue().equals("AVAILABLE"))gaps.add(new EvidenceData.Gap("ROLE_"+entry.getKey().toUpperCase()+"_"+entry.getValue(),null,"角色不可用或已禁用"));
        boolean requiredComplete=requiredComplete(frozen);boolean rolesComplete=roleResults.values().stream().allMatch("AVAILABLE"::equals);
        if(!requiredComplete)gaps.add(new EvidenceData.Gap("REQUIRED_ANALYSIS_INCOMPLETE",null,"所声明的历史风险研究未全部完成"));
        boolean fallback=!judgeStatus.equals("AVAILABLE");String status=requiredComplete && rolesComplete && !fallback?"COMPLETED":"PARTIAL";
        var report=json.createObjectNode();report.put("schemaVersion","1.0.0");report.put("reportId","report_"+UUID.randomUUID());report.put("runId",claim.run().runId());
        report.put("researchSnapshotId",frozen.snapshot().get("snapshotId").asString());report.put("snapshotHash",frozen.snapshot().get("snapshotHash").asString());
        report.set("decisionRef",frozen.snapshot().get("decisionRef"));report.set("portfolioRef",frozen.snapshot().get("portfolioRef"));report.put("status",status);
        report.set("dataMode",frozen.snapshot().get("dataMode"));report.put("dataQuality","PARTIAL");report.put("generationMode",fallback?"DETERMINISTIC_FALLBACK":"MODEL_REVIEW");
        var required=new ArrayList<>(List.of("REALLOCATION","DIRECT_PRICE_SHOCK"));if(!frozen.snapshot().get("dataMode").asString().equals("SYNTHETIC"))required.add("HISTORICAL_RISK");
        report.set("scope",json.valueToTree(Map.of("requiredAnalyses",required,"optionalAnalyses",List.of("MACRO_CAUSAL_TEST"))));
        var assessments=json.createArrayNode();for(JsonNode hypothesis:frozen.decision().get("hypotheses"))assessments.add(json.valueToTree(Map.of("hypothesisId",hypothesis.get("hypothesisId").asString(),
                "supportStatus","UNVERIFIED","evidenceRefs",List.of(),"metricRefs",List.of(),"reason","缺少获准宏观因果证据，组合规则不能验证宏观假设")));
        report.set("hypothesisAssessments",assessments);report.set("claims",json.valueToTree(all));report.set("verdict",verdict);report.set("metricBundle",frozen.metricBundle());report.set("scenarioResult",frozen.scenarioResult());
        report.set("evidenceRefs",json.valueToTree(new FrozenReferences(frozen).modelEvidence().stream().map(EvidenceData.Evidence::evidenceId).sorted().toList()));report.set("roleResults",json.valueToTree(roleResults));
        report.set("gaps",json.valueToTree(gaps));report.set("limitations",json.valueToTree(List.of("冲击是用户假设，不代表价格预测","归一化或绝对金额口径以固定组合为准；未考虑交易成本","宏观因果关系未验证；候选尚未经金融数值校验及原子发布")));
        var versions=json.createObjectNode();versions.put("contractVersion","1.0.0");var manifest=frozen.snapshot().get("manifest");
        for(String key:List.of("engineVersion","qualityPolicyVersion","promptVersions","modelConfigVersion"))versions.set(key,manifest.get(key));report.set("versions",versions);
        return new Candidate("CANDIDATE",report,gaps);
    }
    public boolean minimum(ResearchData.Frozen frozen) {
        if(frozen.scenarioResult()==null || frozen.evidence().stream().noneMatch(value->value.type().equals("USER_INPUT") && value.payloadRef()!=null))return false;
        var names=new HashSet<String>();for(JsonNode metric:frozen.metricBundle().get("metrics"))if(metric.get("status").asString().equals("CALCULABLE"))names.add(metric.get("name").asString());
        return names.containsAll(Set.of("WEIGHT_CHANGE","TARGET_WEIGHT_AFTER","SCENARIO_RETURN_BEFORE","SCENARIO_RETURN_AFTER","SCENARIO_PNL_BEFORE","SCENARIO_PNL_AFTER","SCENARIO_RETURN_CHANGE"))
                && frozen.scenarioResult().get("beforeWeights").size()==frozen.portfolio().get("positions").size() && frozen.scenarioResult().get("ruleResults").size()>=2;
    }
    public boolean requiredComplete(ResearchData.Frozen frozen) {
        if(frozen.snapshot().get("dataMode").asString().equals("SYNTHETIC"))return true;
        if(frozen.snapshot().get("manifest").get("marketInputs").size()!=frozen.portfolio().get("positions").size()-1)return false;
        for(JsonNode metric:frozen.metricBundle().get("metrics"))if(!metric.get("status").asString().equals("CALCULABLE"))return false;
        return !frozen.snapshot().get("manifest").get("marketInputs").isEmpty();
    }
    public JsonNode parseVerdict(JsonNode value,ResearchData.Frozen frozen,List<JsonNode> claims) {
        if(!value.isObject() || value.size()!=4 || !"1.0.0".equals(value.path("schemaVersion").asString()) || !frozen.snapshot().get("runId").equals(value.get("runId"))
                || !frozen.snapshot().get("snapshotHash").equals(value.get("snapshotHash")) || !value.has("verdict"))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        var verdict=value.get("verdict");schemas.require("Verdict",verdict);
        var relations=new HashMap<String,String>();claims.forEach(claim->relations.put(claim.get("claimId").asString(),claim.get("relation").asString()));
        var used=new HashSet<String>();
        for(String field:List.of("supportingClaimIds","opposingClaimIds"))for(JsonNode ref:verdict.get(field)) {
            if(!used.add(ref.asString()) || !Objects.equals(relations.get(ref.asString()),field.equals("supportingClaimIds")?"SUPPORTS":"OPPOSES"))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        }
        if(!verdict.get("verdict").asString().equals("INSUFFICIENT_EVIDENCE"))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        return verdict;
    }
    private RoleReviewService.Review readReview(String run,StepKey key,ResearchData.Frozen frozen) {
        var step=store.step(run,key);
        if(step.status()!=StepStatus.SUCCEEDED || step.outputRef()==null)throw new BusinessException(ErrorCode.INVALID_STATE);
        var review=json.readValue(mapper.output(step.outputRef()),RoleReviewService.Review.class);
        if(!review.role().equals(key.name()) || !review.snapshotHash().equals(frozen.snapshot().get("snapshotHash").asString()))throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        if(review.status().equals("AVAILABLE"))reviews.parse(json.valueToTree(Map.of("schemaVersion","1.0.0","runId",run,"snapshotHash",review.snapshotHash(),"role",review.role(),"claims",review.claims())),frozen,key.name());
        else if(!Set.of("MISSING","DISABLED").contains(review.status()) || !review.claims().isEmpty())throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        return review;
    }
    private JsonNode fallback(){return json.valueToTree(Map.of("schemaVersion","1.0.0","verdict","INSUFFICIENT_EVIDENCE","thesisAssessment","仅保留确定性计算，模型审查不可用且宏观依据未验证", "supportingClaimIds",List.of(),"opposingClaimIds",List.of(),"unknowns",List.of("宏观因果证据不足"),"invalidationConditions",List.of("用户输入或数据口径改变时需重新研究"),"nextChecks",List.of("补充获准证据后重新运行审查")));}
}
