package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.llm.LlmClient;
import org.jack.backend.report.ReportCandidates;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportCandidateTests extends ResearchPipelineSupport {
    @Autowired ReportCandidates candidates;
    @MockitoBean LlmClient client;
    void throughRoles(){for(int index=0;index<2;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}}
    @Test void disabledJudgeProducesPartialDeterministicCandidateWithoutPublishing() throws Exception {
        var run=scenarioRun();throughQuant();throughRoles();var judge=claims.claimNext();var candidate=candidates.assemble(judge,false);
        assertThat(candidate.state()).isEqualTo("CANDIDATE");var report=candidate.report();
        assertThat(report.path("status").asString()).isEqualTo("PARTIAL");assertThat(report.path("generationMode").asString()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(report.path("verdict").path("verdict").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(report.path("roleResults").path("judge").asString()).isEqualTo("DISABLED");
        assertThat(report.has("validatedAt")).isFalse();assertThat(report.has("publishedAt")).isFalse();assertThat(store.get(run.runId()).reportId()).isNull();
        checkpoints.save(judge,output(candidate,StepKey.VALIDATE));assertThat(store.step(run.runId(),StepKey.JUDGE).outputRef()).isNotNull();
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g29-candidate.json"),json.writeValueAsString(candidate));verifyNoInteractions(client,provider);
    }
    @Test void judgeTimeoutRetainsDeterministicResultsAndExplicitMissingRole() throws Exception {
        var run=scenarioRun();throughQuant();throughRoles();var judge=claims.claimNext();when(client.complete(any(),anyLong())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));
        var candidate=candidates.assemble(judge,true);assertThat(candidate.report().path("roleResults").path("judge").asString()).isEqualTo("MISSING");
        assertThat(candidate.report().path("status").asString()).isEqualTo("PARTIAL");assertThat(candidate.report().path("metricBundle").path("metrics")).hasSize(7);verify(client,times(1)).complete(any(),anyLong());
    }
    @Test void withoutExplicitShocksMinimumReportCannotExist() throws Exception {
        var run=scenarioRun();throughQuant();var context=frozen.get(run.runId());
        var missing=new ResearchData.Frozen(context.snapshot(),context.decision(),context.portfolio(),context.plan(),context.metricBundle(),null,context.evidence(),context.instrumentMetadata(),context.inputContent());
        assertThat(candidates.minimum(missing)).isFalse();
    }
    @Test void judgeCannotPromoteMacroHypothesisOrInventClaimReferences() throws Exception {
        var run=scenarioRun();throughQuant();var context=frozen.get(run.runId());
        var verdict=json.valueToTree(Map.of("schemaVersion","1.0.0","verdict","INSUFFICIENT_EVIDENCE","thesisAssessment","宏观证据不足","supportingClaimIds",List.of(),"opposingClaimIds",List.of(),"unknowns",List.of("未验证"),"invalidationConditions",List.of(),"nextChecks",List.of("补充证据")));
        var wire=json.createObjectNode();wire.put("schemaVersion","1.0.0");wire.put("runId",run.runId());wire.set("snapshotHash",context.snapshot().get("snapshotHash"));wire.set("verdict",verdict);
        assertThat(candidates.parseVerdict(wire,context,List.of())).isEqualTo(verdict);
        ((tools.jackson.databind.node.ObjectNode)verdict).put("verdict","SUPPORTED");assertThatThrownBy(()->candidates.parseVerdict(wire,context,List.of())).isInstanceOf(BusinessException.class);
        ((tools.jackson.databind.node.ObjectNode)verdict).put("verdict","INSUFFICIENT_EVIDENCE");((tools.jackson.databind.node.ObjectNode)verdict).set("supportingClaimIds",json.valueToTree(List.of("claim_foreign")));
        assertThatThrownBy(()->candidates.parseVerdict(wire,context,List.of())).isInstanceOf(BusinessException.class);
    }
}
