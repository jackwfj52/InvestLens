package org.jack.backend.research;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.scenario.*;
import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.workflow.WorkflowData.*;

class ResearchFreezeTests extends ResearchPipelineSupport {
    @Test void freezesCanonicalManifestAndBindsOnlyRunOwnedEvidenceAndMetrics() throws Exception {
        var run=scenarioRun();throughQuant();var result=frozen.get(run.runId());
        assertThat(result.snapshot().get("snapshotHash").asString()).isEqualTo(store.get(run.runId()).snapshotHash());
        assertThat(result.scenarioResult().path("runId").asString()).isEqualTo(run.runId());
        assertThat(result.metricBundle().get("metrics")).hasSize(7);
        assertThat(result.metricBundle().get("inputHash").asString()).isEqualTo(org.jack.backend.common.json.CanonicalJson.sha256(result.inputContent()));
        assertThat(result.inputContent().get("parameters").has("minimumSamples")).isTrue();
        assertThat(result.metricBundle().get("metrics").get(2).get("value").asString()).isEqualTo("-0.065");
        assertThat(result.evidence()).allMatch(value->value.runId().equals(run.runId()) && value.type().equals("USER_INPUT"));
        verifyNoInteractions(provider);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g27-frozen.json"),json.writeValueAsString(result));
    }
    @Test void frozenContentSurvivesNewScenarioAndNeverRerunsProviders() throws Exception {
        var run=scenarioRun();throughQuant();String original=json.writeValueAsString(frozen.get(run.runId()));
        var result=frozen.get(run.runId());var other=scenarios.create(new ScenarioData.Create("1.0.0",run.decisionId(),run.decisionVersion(),
                json.readValue(jdbc.queryForObject("SELECT content_json FROM research_tool_results WHERE run_id=?",String.class,run.runId()),ResearchData.Tools.class).calculationSnapshotId(),
                run.portfolioSnapshotId(),List.of(new ScenarioData.Shock(GOLD,"-0.1"),new ScenarioData.Shock(SILVER,"-0.1"),new ScenarioData.Shock(CASH,"0"))));
        try{assertThat(json.writeValueAsString(frozen.get(run.runId()))).isEqualTo(original);assertThat(result.metricBundle().get("metrics").get(3).get("value").asString()).isEqualTo("-0.085");}
        finally{jdbc.update("DELETE FROM scenario_results WHERE scenario_id=?",other.scenarioId());evidenceIds.add(other.sourceEvidenceRef());}
        verifyNoInteractions(provider);
    }
    @Test void scenarioBindingParticipatesInIdempotencyHashAndCannotCrossDecision() throws Exception {
        var run=scenarioRun();var scenarioId=jdbc.queryForObject("SELECT scenario_id FROM research_run_inputs WHERE run_id=?",String.class,run.runId());
        var unrelated=fixture();
        assertThatThrownBy(()->creation.create(unrelated.decisionId(),UUID.randomUUID().toString(),new RunDtos.Create("1.0.0",unrelated.request().decisionVersion(),
                unrelated.request().portfolioSnapshotId(),unrelated.request().portfolioVersion(),unrelated.request().dataMode(),scenarioId))).isInstanceOf(BusinessException.class);
        var key=jdbc.queryForObject("SELECT idempotency_key FROM idempotency_keys WHERE run_id=?",String.class,run.runId());
        assertThatThrownBy(()->creation.create(run.decisionId(),key,new RunDtos.Create("1.0.0",run.decisionVersion(),run.portfolioSnapshotId(),run.portfolioVersion(),run.dataMode()))).isInstanceOf(BusinessException.class);
    }
    @Test void optionalScenarioCannotBeExplicitNullOverHttp() throws Exception {
        var fixture=fixture();var request=(tools.jackson.databind.node.ObjectNode)json.valueToTree(fixture.request());request.putNull("scenarioId");
        var http=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/decisions/"+fixture.decisionId()+"/runs"))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
        assertThat(java.net.http.HttpClient.newHttpClient().send(http,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }
}
