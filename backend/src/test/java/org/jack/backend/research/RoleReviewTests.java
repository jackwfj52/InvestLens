package org.jack.backend.research;

import org.jack.backend.agent.RoleReviewService;
import org.jack.backend.common.exception.*;
import org.jack.backend.llm.LlmClient;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleReviewTests extends ResearchPipelineSupport {
    @Autowired RoleReviewService reviews;
    @MockitoBean LlmClient client;
    ObjectNode claim(ResearchData.Frozen context,String role) {
        var claim=json.createObjectNode();claim.put("schemaVersion","1.0.0");claim.put("claimId","claim_"+role);claim.put("runId",context.snapshot().get("runId").asString());claim.put("snapshotHash",context.snapshot().get("snapshotHash").asString());
        claim.put("role",role);claim.put("hypothesisId",context.decision().get("hypotheses").get(0).get("hypothesisId").asString());claim.put("text","直接情景不验证宏观假设");claim.put("relation",role.equals("BULL")?"SUPPORTS":"OPPOSES");
        claim.set("evidenceRefs",json.valueToTree(List.of(context.evidence().get(0).evidenceId())));claim.set("metricRefs",json.valueToTree(List.of(context.metricBundle().get("metrics").get(0).get("metricId").asString())));
        claim.set("conditions",json.valueToTree(List.of("用户假设成立")));claim.set("invalidationConditions",json.valueToTree(List.of("宏观机制被否定")));claim.set("unknowns",json.valueToTree(List.of("宏观证据未验证")));return claim;
    }
    ObjectNode reply(ResearchData.Frozen context,String role) {
        return json.valueToTree(Map.of("schemaVersion","1.0.0","runId",context.snapshot().get("runId").asString(),"snapshotHash",context.snapshot().get("snapshotHash").asString(),"role",role,"claims",List.of(claim(context,role))));
    }
    LlmClient.Reply wire(String content){return new LlmClient.Reply(content,"stub-model","stop",new LlmClient.Usage(4,3,7));}
    @Test void oneSidedTimeoutPersistsAndOtherSideUsesSameSnapshot() throws Exception {
        var run=scenarioRun();throughQuant();var context=frozen.get(run.runId());var bull=claims.claimNext();
        when(client.complete(any(),anyLong())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT)).thenReturn(wire(json.writeValueAsString(reply(context,"BEAR"))));
        var missing=reviews.review(bull,true);assertThat(missing.status()).isEqualTo("MISSING");checkpoints.save(bull,output(missing,StepKey.BEAR));
        var bear=claims.claimNext();var available=reviews.review(bear,true);checkpoints.save(bear,output(available,StepKey.JUDGE));
        assertThat(available.status()).isEqualTo("AVAILABLE");assertThat(available.snapshotHash()).isEqualTo(missing.snapshotHash());
        assertThat(json.readTree(workflowMapper.output(store.step(run.runId(),StepKey.BULL).outputRef())).get("status").asString()).isEqualTo("MISSING");
        assertThat(store.get(run.runId()).modelAttempts()).isEqualTo(2);verify(client,times(2)).complete(any(),anyLong());verifyNoInteractions(provider);
    }
    @Test void rejectsUnknownReferencesCrossRunAndWrongRole() throws Exception {
        var run=scenarioRun();throughQuant();var context=frozen.get(run.runId());var valid=reply(context,"BULL");assertThat(reviews.parse(valid,context,"BULL").claims()).hasSize(1);
        for(String field:List.of("runId","snapshotHash","role")) {
            var invalid=valid.deepCopy();((ObjectNode)invalid.get("claims").get(0)).put(field,"foreign");assertThatThrownBy(()->reviews.parse(invalid,context,"BULL")).isInstanceOf(BusinessException.class);
        }
        var invalid=valid.deepCopy();((ObjectNode)invalid.get("claims").get(0)).set("evidenceRefs",json.valueToTree(List.of("E99")));
        assertThatThrownBy(()->reviews.parse(invalid,context,"BULL")).isInstanceOf(BusinessException.class);
    }
    @Test void schemaRepairsAreGlobalAcrossRoles() throws Exception {
        var run=scenarioRun();throughQuant();when(client.complete(any(),anyLong())).thenReturn(wire("{}"));
        var bull=claims.claimNext();var first=reviews.review(bull,true);checkpoints.save(bull,output(first,StepKey.BEAR));
        var bear=claims.claimNext();var second=reviews.review(bear,true);
        assertThat(first.status()).isEqualTo("MISSING");assertThat(second.status()).isEqualTo("MISSING");
        assertThat(store.get(run.runId()).schemaRepairs()).isEqualTo(1);assertThat(store.get(run.runId()).modelAttempts()).isEqualTo(3);
        verify(client,times(3)).complete(any(),anyLong());
    }
    @Test void disabledReviewNeverCallsModelAndContinuesToJudge() throws Exception {
        var run=scenarioRun();throughQuant();for(int index=0;index<2;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}
        assertThat(claims.claimNext().step().stepKey()).isEqualTo(StepKey.JUDGE);verifyNoInteractions(client);
    }
}
