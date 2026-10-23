package org.jack.backend.research;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.report.*;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class ReportStructureTests extends ResearchPipelineSupport {
    @Autowired ReportStructureValidator validator;
    @Autowired ReportCandidates candidates;
    @Autowired ContractSchemas schemas;
    record Ready(String runId,ObjectNode report) { }
    Ready ready() throws Exception {
        var run=scenarioRun();throughQuant();for(int index=0;index<2;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}
        var judge=claims.claimNext();var candidate=candidates.assemble(judge,false);checkpoints.save(judge,output(candidate,StepKey.VALIDATE));return new Ready(run.runId(),(ObjectNode)candidate.report());
    }
    @Test void validCandidateReferencesResolveAndPublishesAtomically() throws Exception {
        var ready=ready();var valid=validator.validate(ready.runId(),ready.report());
        assertThat(valid.status()).isEqualTo("STRUCTURE_VALID");assertThat(valid.published()).isFalse();assertThat(valid.numericValidationComplete()).isFalse();
        var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));
        var run=store.get(ready.runId());assertThat(run.status()).isEqualTo(RunStatus.PARTIAL);assertThat(run.errorCode()).isNull();assertThat(run.reportId()).isNotNull();
        assertThat(store.step(run.runId(),StepKey.VALIDATE).status()).isEqualTo(StepStatus.SUCCEEDED);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g30-candidate.json"),json.writeValueAsString(ready.report()));
    }
    @Test void rejectsUnknownCrossRunAndMismatchedSnapshotReferences() throws Exception {
        var first=ready();var pending=claims.claimNext();checkpoints.save(pending,handler.execute(pending));var second=ready();
        var invalid=first.report().deepCopy();invalid.set("evidenceRefs",second.report().get("evidenceRefs"));assertThatThrownBy(()->validator.validate(first.runId(),invalid)).isInstanceOf(BusinessException.class);
        for(String field:List.of("runId","researchSnapshotId","snapshotHash")) {
            var changed=first.report().deepCopy();changed.put(field,second.report().get(field).asString());assertThatThrownBy(()->validator.validate(first.runId(),changed)).isInstanceOf(BusinessException.class);
        }
        var unknown=first.report().deepCopy();unknown.set("evidenceRefs",json.valueToTree(List.of("E99")));assertThatThrownBy(()->validator.validate(first.runId(),unknown)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsSchemaViolationsMetricChangesFalseRoleAndMacroPromotion() throws Exception {
        var ready=ready();List<Consumer<ObjectNode>> corruptions=List.of(
                report->report.put("extra","unsupported"),report->report.remove("limitations"),report->report.put("publishedAt","2026-10-23T00:00:00.000Z"),
                report->((ObjectNode)report.get("metricBundle").get("metrics").get(0)).put("value","999"),
                report->((ObjectNode)report.get("roleResults")).put("judge","AVAILABLE"),
                report->((ObjectNode)report.get("hypothesisAssessments").get(0)).put("supportStatus","SUPPORTED"),
                report->((ObjectNode)report.get("scenarioResult")).set("metricRefs",json.valueToTree(List.of("metric_foreign"))),
                report->((ObjectNode)report.get("verdict")).set("supportingClaimIds",json.valueToTree(List.of("claim_foreign"))));
        for(var corrupt:corruptions){var invalid=ready.report().deepCopy();corrupt.accept(invalid);assertThatThrownBy(()->validator.validate(ready.runId(),invalid)).isInstanceOf(BusinessException.class);}
    }
    @Test void cancelledValidationCannotSaveLateCandidate() throws Exception {
        var ready=ready();var claim=claims.claimNext();var result=handler.execute(claim);store.cancel(ready.runId());
        assertThatThrownBy(()->checkpoints.save(claim,result)).isInstanceOf(BusinessException.class);assertThat(store.get(ready.runId()).status()).isEqualTo(RunStatus.CANCELLED);
    }
    @Test void frozenReportSchemaAndStrictJsonMatchG01Fixture() throws Exception {
        var example=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/contracts/examples/gold-silver-cash.json")));
        schemas.require("Report",example.get("report"));
        var invalid=(ObjectNode)example.get("report").deepCopy();invalid.put("status","COMPLETED");assertThatThrownBy(()->schemas.require("Report",invalid)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->schemas.parse("{\"a\":1,\"a\":2}")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->schemas.parse("{} {} ")).isInstanceOf(BusinessException.class);
    }
}
