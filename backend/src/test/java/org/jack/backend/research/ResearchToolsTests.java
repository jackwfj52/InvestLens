package org.jack.backend.research;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.workflow.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchToolsTests extends WorkflowIntegrationSupport {
    @Autowired ToolRegistry tools;
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowCheckpointService checkpoints;
    @Autowired WorkflowHandler handler;
    WorkflowClaimService.Claim research() throws Exception {
        queued(fixture());var plan=claims.claimNext();checkpoints.save(plan,handler.execute(plan));return claims.claimNext();
    }
    ObjectNode plan(WorkflowClaimService.Claim claim) {
        var decision=decisions.requireConfirmed(claim.run().decisionId(),claim.run().decisionVersion());
        return json.valueToTree(Map.of("schemaVersion","1.0.0","planId","plan_test","runId",claim.run().runId(),
                "decisionRef",Map.of("decisionId",decision.decisionId(),"version",decision.version()),"hypothesisIds",decision.hypotheses().stream().map(h->h.hypothesisId()).toList(),
                "dataNeeds",List.of(),"steps",List.of(Map.of("stepKey","portfolio","tool","Portfolio.getSnapshot","dependsOn",List.of()),
                Map.of("stepKey","quant","tool","Quant.calculate","dependsOn",List.of("portfolio"))),"promptVersion","planner-v1"));
    }
    @Test void fixedParametersAndDurableReuseWithoutRepeatedWrites() throws Exception {
        var claim=research();var plan=plan(claim);var output=tools.execute(claim,plan,null);track(evidence.snapshot(output.calculationSnapshotId()));
        assertThat(output.gaps()).extracting(gap->gap.code()).contains("SCENARIO_INPUT_MISSING","HISTORICAL_RISK_MISSING");
        assertThat(tools.execute(claim,plan,null)).isEqualTo(output);verifyNoInteractions(provider);
        plan.put("planId","plan_changed");assertThatThrownBy(()->tools.execute(claim,plan,null)).isInstanceOf(BusinessException.class);
    }
    @Test void unknownToolsAndParameterInjectionNeverReachProvider() throws Exception {
        var claim=research();var plan=plan(claim);((ObjectNode)plan.get("steps").get(0)).put("url","https://example.invalid");
        assertThatThrownBy(()->tools.execute(claim,plan,null)).isInstanceOf(BusinessException.class);verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_tool_results WHERE run_id=?",Integer.class,claim.run().runId())).isZero();
    }
    @Test void cancellationPreventsToolMaterialization() throws Exception {
        var claim=research();store.cancel(claim.run().runId());assertThatThrownBy(()->tools.execute(claim,plan(claim),null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(provider);
    }
}
