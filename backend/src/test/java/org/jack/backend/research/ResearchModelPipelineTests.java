package org.jack.backend.research;

import org.jack.backend.llm.LlmClient;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties="INVESTLENS_LLM_ENABLED=true")
class ResearchModelPipelineTests extends ResearchPipelineSupport {
    @MockitoBean LlmClient client;
    @Test void fourNormalModelCallsShareFrozenInputsAndReachUnpublishedValidatedCandidate() throws Exception {
        var run=scenarioRun();var hashes=new ArrayList<String>();
        when(client.complete(any(),anyLong())).thenAnswer(invocation->{
            LlmClient.Request request=invocation.getArgument(0);var input=json.readTree(request.input());ObjectNode reply;
            if(input.has("fixed")) {
                var fixed=input.get("fixed");assertThat(input.get("scenarioInputBound").asBoolean()).isTrue();
                reply=json.valueToTree(Map.of("schemaVersion","1.0.0","planId","plan_stub","runId",fixed.get("runId").asString(),
                        "decisionRef",Map.of("decisionId",fixed.get("decisionId").asString(),"version",fixed.get("decisionVersion").asInt()),"hypothesisIds",fixed.get("hypothesisIds"),"dataNeeds",List.of(),
                        "steps",List.of(Map.of("stepKey","portfolio","tool","Portfolio.getSnapshot","dependsOn",List.of()),Map.of("stepKey","scenario","tool","Quant.runScenario","dependsOn",List.of("portfolio"))),"promptVersion","planner-v1"));
            } else {
                hashes.add(input.get("snapshotHash").asString());reply=json.createObjectNode();reply.put("schemaVersion","1.0.0");reply.set("runId",input.get("runId"));reply.set("snapshotHash",input.get("snapshotHash"));
                if(input.has("role")){reply.set("role",input.get("role"));reply.set("claims",json.createArrayNode());}
                else reply.set("verdict",json.valueToTree(Map.of("schemaVersion","1.0.0","verdict","INSUFFICIENT_EVIDENCE","thesisAssessment","宏观依据仍未验证","supportingClaimIds",List.of(),"opposingClaimIds",List.of(),"unknowns",List.of("缺少宏观证据"),"invalidationConditions",List.of(),"nextChecks",List.of("补充获准证据"))));
            }
            return new LlmClient.Reply(json.writeValueAsString(reply),"stub-model","stop",new LlmClient.Usage(4,3,7));
        });
        for(int index=0;index<7;index++){var claim=claims.claimNext();assertThat(claim).isNotNull();checkpoints.save(claim,handler.execute(claim));}
        assertThat(store.get(run.runId()).status()).isEqualTo(RunStatus.FAILED);assertThat(store.get(run.runId()).errorCode()).isEqualTo("REPORT_UNAVAILABLE");
        assertThat(store.get(run.runId()).modelAttempts()).isEqualTo(4);assertThat(store.get(run.runId()).schemaRepairs()).isZero();assertThat(hashes).hasSize(3).containsOnly(store.get(run.runId()).snapshotHash());
        var validated=json.readTree(workflowMapper.output(store.step(run.runId(),StepKey.VALIDATE).outputRef()));assertThat(validated.get("report").get("status").asString()).isEqualTo("COMPLETED");
        assertThat(validated.get("validation").get("published").asBoolean()).isFalse();verify(client,times(4)).complete(any(),anyLong());verifyNoInteractions(provider);
    }
}
