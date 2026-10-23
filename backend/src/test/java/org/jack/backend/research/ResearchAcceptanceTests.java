package org.jack.backend.research;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.llm.LlmClient;
import org.jack.backend.scenario.ScenarioData;
import org.jack.backend.workflow.WorkflowData.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties="INVESTLENS_LLM_ENABLED=true")
class ResearchAcceptanceTests extends ResearchPipelineSupport {
    @MockitoBean LlmClient client;
    final List<String> reviewHashes=new ArrayList<>(), extraScenarios=new ArrayList<>();
    String sourceCalculationSnapshotId;

    @AfterEach void cleanExtraScenarios() {
        extraScenarios.forEach(id->jdbc.update("DELETE FROM scenario_results WHERE scenario_id=?",id));
    }

    @BeforeEach void localModel() throws Exception {
        when(client.complete(any(),anyLong())).thenAnswer(invocation->{
            LlmClient.Request request=invocation.getArgument(0);
            var input=json.readTree(request.input());ObjectNode reply;
            if(input.has("fixed")) {
                var fixed=input.get("fixed");assertThat(input.get("scenarioInputBound").asBoolean()).isTrue();
                reply=json.valueToTree(Map.of("schemaVersion","1.0.0","planId","plan_acceptance","runId",fixed.get("runId").asString(),
                        "decisionRef",Map.of("decisionId",fixed.get("decisionId").asString(),"version",fixed.get("decisionVersion").asInt()),
                        "hypothesisIds",fixed.get("hypothesisIds"),"dataNeeds",List.of(),"steps",List.of(
                                Map.of("stepKey","portfolio","tool","Portfolio.getSnapshot","dependsOn",List.of()),
                                Map.of("stepKey","metrics","tool","Quant.calculate","dependsOn",List.of("portfolio")),
                                Map.of("stepKey","scenario","tool","Quant.runScenario","dependsOn",List.of("portfolio"))),"promptVersion","planner-v1"));
            } else {
                reviewHashes.add(input.get("snapshotHash").asString());
                reply=json.createObjectNode();reply.put("schemaVersion","1.0.0");reply.set("runId",input.get("runId"));reply.set("snapshotHash",input.get("snapshotHash"));
                if(input.has("role")) {
                    var context=frozen.get(input.get("runId").asString());String role=input.get("role").asString();
                    var claim=json.createObjectNode();claim.put("schemaVersion","1.0.0");claim.put("claimId","claim_acceptance_"+role);
                    claim.set("runId",input.get("runId"));claim.set("snapshotHash",input.get("snapshotHash"));claim.put("role",role);
                    claim.set("hypothesisId",context.decision().get("hypotheses").get(0).get("hypothesisId"));
                    claim.put("text",role.equals("BULL")?"用户调仓目标可检验，但宏观机制尚未验证":"直接冲击揭示风险，不能据此确认宏观假设");
                    claim.put("relation",role.equals("BULL")?"SUPPORTS":"OPPOSES");
                    claim.set("evidenceRefs",json.valueToTree(List.of(context.evidence().get(0).evidenceId())));
                    claim.set("metricRefs",json.valueToTree(List.of(context.metricBundle().get("metrics").get(0).get("metricId").asString())));
                    claim.set("conditions",json.valueToTree(List.of("用户显式冲击成立")));
                    claim.set("invalidationConditions",json.valueToTree(List.of("固定输入或因果机制改变")));
                    claim.set("unknowns",json.valueToTree(List.of("缺少获准宏观因果证据")));
                    reply.set("role",input.get("role"));reply.set("claims",json.valueToTree(List.of(claim)));
                } else {
                    assertThat(input.get("claims").size()).isEqualTo(2);
                    reply.set("verdict",json.valueToTree(Map.of("schemaVersion","1.0.0","verdict","INSUFFICIENT_EVIDENCE",
                            "thesisAssessment","直接情景不等于宏观验证","supportingClaimIds",List.of("claim_acceptance_BULL"),
                            "opposingClaimIds",List.of("claim_acceptance_BEAR"),"unknowns",List.of("宏观因果证据缺失"),
                            "invalidationConditions",List.of("组合输入改变"),"nextChecks",List.of("补充获准证据"))));
                }
            }
            return new LlmClient.Reply(json.writeValueAsString(reply),"local-acceptance-stub","stop",new LlmClient.Usage(4,3,7));
        });
    }

    private String createResearch() throws Exception {
        var portfolio=portfolio();var query=history(31,"PAYLOAD_ALLOWED");var input=snapshot(portfolio,List.of(query));
        sourceCalculationSnapshotId=input.snapshotId();
        var fixture=fixture(portfolio);
        var scenario=scenarios.create(new ScenarioData.Create("1.0.0",fixture.decisionId(),fixture.request().decisionVersion(),input.snapshotId(),portfolio.snapshotId(),
                List.of(new ScenarioData.Shock(GOLD,"-0.2"),new ScenarioData.Shock(SILVER,"-0.25"),new ScenarioData.Shock(CASH,"0"))));
        evidenceIds.add(scenario.sourceEvidenceRef());
        String body=json.writeValueAsString(Map.of("schemaVersion","1.0.0","decisionVersion",fixture.request().decisionVersion(),
                "portfolioSnapshotId",portfolio.snapshotId(),"portfolioVersion",portfolio.version(),"dataMode","SYNTHETIC","scenarioId",scenario.scenarioId()));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/decisions/"+fixture.decisionId()+"/runs"))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var http=HttpClient.newHttpClient();var first=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(202);String runId=json.readTree(first.body()).get("data").get("runId").asString();runIds.add(runId);
        var duplicate=http.send(request,HttpResponse.BodyHandlers.ofString());assertThat(duplicate.statusCode()).isEqualTo(202);
        assertThat(json.readTree(duplicate.body()).get("data").get("runId").asString()).isEqualTo(runId);
        return runId;
    }

    private void executeSteps(int count) {
        for(int index=0;index<count;index++) {
            var claim=claims.claimNext();assertThat(claim).isNotNull();checkpoints.save(claim,handler.execute(claim));
        }
    }

    private JsonNode read(String path) throws Exception {
        var response=send("GET",path,null);assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("data");
    }

    @Test void syntheticHistoryAndFourLocalModelCallsPublishCitedReportWithoutChangingOriginalOnRecalculation() throws Exception {
        String runId=createResearch();executeSteps(7);var run=store.get(runId);var context=frozen.get(runId);
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);assertThat(run.modelAttempts()).isEqualTo(4);
        assertThat(run.dataAttempts()).isZero();assertThat(run.schemaRepairs()).isZero();assertThat(run.retryCount()).isZero();
        assertThat(run.finishedAt()).isBeforeOrEqualTo(run.deadlineAt());assertThat(reviewHashes).hasSize(3).containsOnly(run.snapshotHash());
        assertThat(context.snapshot().get("manifest").get("marketInputs").size()).isEqualTo(1);
        var report=read("/api/runs/"+runId+"/report");
        assertThat(report.get("generationMode").asString()).isEqualTo("MODEL_REVIEW");assertThat(report.get("dataMode").asString()).isEqualTo("SYNTHETIC");
        assertThat(report.get("dataQuality").asString()).isEqualTo("PARTIAL");assertThat(report.get("claims").size()).isEqualTo(2);
        assertThat(report.get("verdict").get("verdict").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.get("hypothesisAssessments").get(0).get("supportStatus").asString()).isEqualTo("UNVERIFIED");
        var board=read("/api/runs/"+runId+"/evidence");var evidenceRefs=new HashSet<String>();var metricRefs=new HashSet<String>();
        for(JsonNode item:board.get("items")) {
            evidenceRefs.add(item.get("evidenceId").asString());assertThat(item.get("runId").asString()).isEqualTo(runId);
            if(item.get("provider").asString().equals("SYNTHETIC"))assertThat(item.get("retrievedAt").asString()).isEqualTo("2026-10-23T00:00:00.000Z");
        }
        for(JsonNode item:report.get("metricBundle").get("metrics"))metricRefs.add(item.get("metricId").asString());
        for(JsonNode claim:report.get("claims")) {
            for(JsonNode ref:claim.get("evidenceRefs"))assertThat(evidenceRefs).contains(ref.asString());
            for(JsonNode ref:claim.get("metricRefs"))assertThat(metricRefs).contains(ref.asString());
        }
        var figures=read("/api/runs/"+runId+"/report/figures");
        assertThat(figures.toString()).contains("SCENARIO_RETURN_AFTER","-8.50%","PERIOD_RETURN");
        var recalculated=scenarios.create(new ScenarioData.Create("1.0.0",run.decisionId(),run.decisionVersion(),
                sourceCalculationSnapshotId,
                run.portfolioSnapshotId(),List.of(new ScenarioData.Shock(GOLD,"-0.1"),new ScenarioData.Shock(SILVER,"-0.25"),new ScenarioData.Shock(CASH,"0"))));
        extraScenarios.add(recalculated.scenarioId());evidenceIds.add(recalculated.sourceEvidenceRef());
        assertThat(recalculated.scenarioId()).isNotEqualTo(context.scenarioResult().get("scenarioId").asString());
        assertThat(read("/api/runs/"+runId+"/report")).isEqualTo(report);
        assertThat(read("/api/decisions/"+run.decisionId()+"/runs").get("items").toString()).contains(runId,run.reportId());
        assertThat(read("/api/runs/"+runId+"/events?afterSequence=1").get("items").get(0).get("sequence").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_runs WHERE decision_id=?",Integer.class,run.decisionId())).isEqualTo(1);
        verify(client,times(4)).complete(any(),anyLong());verify(provider,times(1)).history(any());
        Files.writeString(Path.of("target/g38-model-stub-report.json"),json.writeValueAsString(report));
    }

    @Test void cancellationAfterValidatedOutputIsComputedPreventsLatePublication() throws Exception {
        String runId=createResearch();executeSteps(6);var claim=claims.claimNext();assertThat(claim.step().stepKey()).isEqualTo(StepKey.VALIDATE);
        var output=handler.execute(claim);assertThat(send("POST","/api/runs/"+runId+"/cancel",null).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->checkpoints.save(claim,output)).isInstanceOf(BusinessException.class);
        assertThat(store.get(runId).status()).isEqualTo(RunStatus.CANCELLED);assertThat(store.get(runId).reportId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE run_id=?",Integer.class,runId)).isZero();
        assertThat(store.events(runId).stream().filter(event->event.type()==EventType.RUN_TERMINATED)).hasSize(1);
        assertThat(send("GET","/api/runs/"+runId+"/report",null).statusCode()).isEqualTo(409);
        verify(client,times(4)).complete(any(),anyLong());
    }
}
