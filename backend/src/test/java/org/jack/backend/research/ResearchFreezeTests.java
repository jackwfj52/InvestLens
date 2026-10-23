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

class ResearchFreezeTests extends WorkflowIntegrationSupport {
    @Autowired protected ScenarioService scenarios;
    @Autowired protected WorkflowClaimService claims;
    @Autowired protected WorkflowCheckpointService checkpoints;
    @Autowired protected WorkflowHandler handler;
    @Autowired protected ResearchFreezeService frozen;
    WorkflowData.Run scenarioRun() throws Exception {
        var portfolio=portfolio();var input=snapshot(portfolio,List.of());var fixture=fixture(portfolio);
        var scenario=scenarios.create(new ScenarioData.Create("1.0.0",fixture.decisionId(),fixture.request().decisionVersion(),input.snapshotId(),portfolio.snapshotId(),
                List.of(new ScenarioData.Shock(GOLD,"-0.2"),new ScenarioData.Shock(SILVER,"-0.25"),new ScenarioData.Shock(CASH,"0"))));
        evidenceIds.add(scenario.sourceEvidenceRef());
        var run=creation.create(fixture.decisionId(),UUID.randomUUID().toString(),new RunDtos.Create("1.0.0",fixture.request().decisionVersion(),portfolio.snapshotId(),portfolio.version(),portfolio.dataMode(),scenario.scenarioId()));
        runIds.add(run.runId());return run;
    }
    void throughQuant() {for(int index=0;index<3;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}}
    @Test void freezesCanonicalManifestAndBindsOnlyRunOwnedEvidenceAndMetrics() throws Exception {
        var run=scenarioRun();throughQuant();var result=frozen.get(run.runId());
        assertThat(result.snapshot().get("snapshotHash").asString()).isEqualTo(store.get(run.runId()).snapshotHash());
        assertThat(result.scenarioResult().path("runId").asString()).isEqualTo(run.runId());
        assertThat(result.metricBundle().get("metrics")).hasSize(7);
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
}
