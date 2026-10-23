package org.jack.backend.support;

import org.jack.backend.decision.*;
import org.jack.backend.workflow.*;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@TestPropertySource(properties="investlens.workflow.scheduler.enabled=false")
public abstract class WorkflowIntegrationSupport extends CalculationIntegrationSupport {
    @Autowired protected WorkflowStore store;
    @Autowired protected WorkflowMapper workflowMapper;
    @Autowired protected RunCreationService creation;
    @Autowired protected DecisionService decisions;
    protected final List<String> runIds=new ArrayList<>(), decisionIds=new ArrayList<>();
    protected record Fixture(String decisionId, RunDtos.Create request) { }
    protected Fixture fixture() throws Exception {
        return fixture(portfolio());
    }
    protected Fixture fixture(org.jack.backend.portfolio.PortfolioDtos.Snapshot p) throws Exception {
        var draft=(ObjectNode)json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        draft.put("inputStatus","CONFIRMED"); ((ObjectNode)draft.get("proposedDecision")).put("portfolioSnapshotId",p.snapshotId());
        var d=decisions.create(json.treeToValue(draft,DecisionDtos.Draft.class)); decisionIds.add(d.decisionId());
        return new Fixture(d.decisionId(),new RunDtos.Create("1.0.0",d.version(),p.snapshotId(),p.version(),p.dataMode()));
    }
    protected WorkflowData.Run queued(Fixture f) {
        var r=creation.create(f.decisionId(),UUID.randomUUID().toString(),f.request());runIds.add(r.runId());return r;
    }
    @AfterEach void cleanRunRows() {
        var selectedScenarios=new ArrayList<String>();
        runIds.forEach(id->{
            selectedScenarios.addAll(jdbc.queryForList("SELECT scenario_id FROM research_run_inputs WHERE run_id=?",String.class,id));
            evidenceIds.addAll(jdbc.queryForList("SELECT evidence_id FROM research_evidence WHERE run_id=?",String.class,id));
            var toolRows=jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(content_json,'$.calculationSnapshotId')) FROM research_tool_results WHERE run_id=?",String.class,id);
            for(String snapshotId:toolRows)if(!snapshotIds.contains(snapshotId))snapshotIds.add(snapshotId);
            jdbc.update("DELETE FROM tool_calls WHERE run_id=?",id);jdbc.update("DELETE FROM run_data_windows WHERE run_id=?",id);
            jdbc.update("DELETE FROM step_outputs WHERE run_id=?",id);
            jdbc.update("DELETE FROM idempotency_keys WHERE run_id=?",id);
            jdbc.update("DELETE FROM run_metric_bundles WHERE run_id=?",id);jdbc.update("DELETE FROM run_calculation_inputs WHERE run_id=?",id);
            jdbc.update("DELETE FROM run_events WHERE run_id=?",id);jdbc.update("DELETE FROM run_steps WHERE run_id=?",id);jdbc.update("DELETE FROM research_runs WHERE run_id=?",id);
        });
        selectedScenarios.forEach(id->jdbc.update("DELETE FROM scenario_results WHERE scenario_id=?",id));
        decisionIds.forEach(id->{jdbc.update("DELETE FROM decision_versions WHERE decision_id=?",id);jdbc.update("DELETE FROM decisions WHERE decision_id=?",id);});
    }
}
