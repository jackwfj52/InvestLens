package org.jack.backend.workflow;

import java.util.Map;
import org.jack.backend.workflow.WorkflowClaimService.Claim;
import static org.jack.backend.workflow.WorkflowData.*;

/** Runs outside the claiming/checkpoint transactions. No fabricated model result is allowed. */
public interface WorkflowHandler {
    record Result(Map<String,Object> output, StepKey next, String failureCode) {
        public Result { output=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(output)); }
    }
    Result execute(Claim claim);
}
