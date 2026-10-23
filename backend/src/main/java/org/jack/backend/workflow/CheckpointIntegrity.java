package org.jack.backend.workflow;

import org.jack.backend.common.json.CanonicalJson;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static org.jack.backend.workflow.WorkflowData.*;

@Component
public class CheckpointIntegrity {
    private final WorkflowMapper mapper;
    private final ObjectMapper json;
    public CheckpointIntegrity(WorkflowMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    public boolean valid(String runId) {
        var hashes=new java.util.HashSet<String>();
        for(Step step:mapper.steps(runId)) {
            if(step.status()!=StepStatus.SUCCEEDED)continue;
            var output=mapper.checkpoint(step.outputRef());
            if(output==null || !runId.equals(output.runId()) || !step.stepId().equals(output.stepId())
                    || step.executionVersion()!=output.executionVersion() || !step.inputHash().equals(output.inputHash()))return false;
            try{if(!output.outputHash().equals(CanonicalJson.sha256(json.readTree(output.outputJson()))))return false;}
            catch(RuntimeException invalid){return false;}
            hashes.add(output.outputHash());
        }
        for(Step step:mapper.steps(runId))if(step.stepKey()!=StepKey.PLAN && !hashes.contains(step.inputHash()))return false;
        return true;
    }
}
