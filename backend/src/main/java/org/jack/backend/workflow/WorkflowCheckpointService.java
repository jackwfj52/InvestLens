package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.CanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.jack.backend.workflow.WorkflowData.*;
import static org.jack.backend.workflow.WorkflowClaimService.Claim;

@Service
public class WorkflowCheckpointService {
    private final WorkflowClaimService claims;
    private final WorkflowMapper mapper;
    private final WorkflowStore store;
    private final ObjectMapper json;
    private final org.jack.backend.report.ReportService reports;
    public WorkflowCheckpointService(WorkflowClaimService claims,WorkflowMapper mapper,WorkflowStore store,ObjectMapper json,org.jack.backend.report.ReportService reports){this.claims=claims;this.mapper=mapper;this.store=store;this.json=json;this.reports=reports;}
    @Transactional
    public void save(Claim claim,WorkflowHandler.Result result) {
        boolean structureOnly=claim.step().stepKey()==StepKey.VALIDATE && "REPORT_CANDIDATE_VALIDATED".equals(result.output().get("kind"));
        if(structureOnly && result.failureCode()==null && store.get(claim.run().runId()).reportId()!=null) {
            var saved=store.step(claim.run().runId(),StepKey.VALIDATE);
            if(saved.status()==StepStatus.SUCCEEDED && saved.executionVersion()==claim.step().executionVersion()
                    && CanonicalJson.sha256(json.readTree(mapper.output(saved.outputRef()))).equals(CanonicalJson.sha256(json.valueToTree(result.output()))))return;
        }
        claims.requireLive(claim);
        if(claim.step().inputHash()==null || (result.failureCode()==null && result.next()==null && !structureOnly))throw new BusinessException(ErrorCode.INVALID_STATE);
        String outputId="output_"+UUID.randomUUID();String hash=CanonicalJson.sha256(json.valueToTree(result.output()));
        mapper.insertOutput(outputId,claim.step(),hash,json.writeValueAsString(result.output()));
        store.transitionStep(claim.run().runId(),claim.step().stepKey(),result.failureCode()==null?StepStatus.SUCCEEDED:StepStatus.FAILED,
                claim.step().executionVersion(),null,outputId,result.failureCode());
        if(result.failureCode()!=null) {
            Run run=store.get(claim.run().runId());store.transition(run.runId(),run.revision(),RunStatus.FAILED,run.stage(),result.failureCode());
        } else if(structureOnly) {
            reports.publish(claim.run().runId(),json.valueToTree(result.output().get("report")));
        } else store.addNextStep(claim.run().runId(),result.next(),hash);
    }
    @Transactional
    public void fail(Claim claim,String code) { save(claim,new WorkflowHandler.Result(Map.of("kind","STEP_FAILURE","errorCode",code),null,code)); }
}
