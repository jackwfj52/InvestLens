package org.jack.backend.research;

import org.jack.backend.scenario.*;
import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.workflow.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;

public abstract class ResearchPipelineSupport extends WorkflowIntegrationSupport {
    @Autowired protected ScenarioService scenarios;
    @Autowired protected WorkflowClaimService claims;
    @Autowired protected WorkflowCheckpointService checkpoints;
    @Autowired protected WorkflowHandler handler;
    @Autowired protected ResearchFreezeService frozen;
    protected WorkflowData.Run scenarioRun() throws Exception {
        var portfolio=portfolio();var input=snapshot(portfolio,List.of());var fixture=fixture(portfolio);
        var scenario=scenarios.create(new ScenarioData.Create("1.0.0",fixture.decisionId(),fixture.request().decisionVersion(),input.snapshotId(),portfolio.snapshotId(),
                List.of(new ScenarioData.Shock(GOLD,"-0.2"),new ScenarioData.Shock(SILVER,"-0.25"),new ScenarioData.Shock(CASH,"0"))));
        evidenceIds.add(scenario.sourceEvidenceRef());
        var run=creation.create(fixture.decisionId(),UUID.randomUUID().toString(),new RunDtos.Create("1.0.0",fixture.request().decisionVersion(),portfolio.snapshotId(),portfolio.version(),portfolio.dataMode(),scenario.scenarioId()));
        runIds.add(run.runId());return run;
    }
    protected void throughQuant() {for(int index=0;index<3;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}}
    protected WorkflowHandler.Result output(Object value,WorkflowData.StepKey next) {
        return new WorkflowHandler.Result(json.convertValue(json.valueToTree(value),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}),next,null);
    }
}
