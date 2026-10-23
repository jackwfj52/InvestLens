package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import static org.jack.backend.workflow.WorkflowData.*;

@Service
public class WorkflowClaimService {
    public record Claim(Run run, Step step, RunDtos.RemainingBudget budget) { }
    private final WorkflowMapper mapper;
    private final WorkflowStore store;
    private final CurrentOwner owner;
    private final Clock clock;
    public WorkflowClaimService(WorkflowMapper mapper, WorkflowStore store, CurrentOwner owner, Clock clock) {
        this.mapper=mapper;this.store=store;this.owner=owner;this.clock=clock;
    }
    @Transactional
    public Claim claimNext() {
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS),ZoneOffset.UTC);
        String id=mapper.claimable(owner.id(),now); if(id==null)return null;
        Run run=store.get(id);
        if(!run.deadlineAt().isAfter(now)) { store.expire(id);return null; }
        Step step=mapper.claimableStep(id,now); if(step==null)return null;
        if(run.status()==RunStatus.QUEUED) run=store.transition(id,run.revision(),RunStatus.RUNNING,step.stepKey().stage(),null);
        if(step.status()==StepStatus.RUNNING) step=store.transitionStep(id,step.stepKey(),StepStatus.PENDING,step.executionVersion(),null,null,"LEASE_EXPIRED");
        LocalDateTime lease=now.plusSeconds(45); if(lease.isAfter(run.deadlineAt()))lease=run.deadlineAt();
        step=store.transitionStep(id,step.stepKey(),StepStatus.RUNNING,step.executionVersion(),lease,null,null);
        run=store.get(id);return new Claim(run,step,RunDtos.remaining(run,clock));
    }
    /** Must run in the same transaction as every fenced output or counter write. */
    public void requireLive(Claim claim) {
        Run run=mapper.lockRun(claim.run().runId(),owner.id());
        Step current=mapper.step(claim.run().runId(),claim.step().stepKey());
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        if(run!=null && run.status()==RunStatus.RUNNING && !run.deadlineAt().isAfter(now))throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
        if(run==null || run.status()!=RunStatus.RUNNING || current==null || current.status()!=StepStatus.RUNNING
                || current.executionVersion()!=claim.step().executionVersion() || !current.leaseUntil().isAfter(now)) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
}
