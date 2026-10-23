package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.evidence.EvidenceService;
import org.jack.backend.portfolio.PortfolioService;
import org.jack.backend.quant.MetricService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.jack.backend.workflow.WorkflowData.*;

/** Transactional state boundary. A validated report publisher remains a separate, later capability. */
@Service
public class WorkflowStore {
    private final WorkflowMapper mapper;
    private final CurrentOwner owner;
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    private final EvidenceService evidence;
    private final MetricService metrics;
    private final Clock clock;
    public WorkflowStore(WorkflowMapper mapper, CurrentOwner owner, DecisionService decisions, PortfolioService portfolios,
                         EvidenceService evidence, MetricService metrics, Clock clock) {
        this.mapper = mapper; this.owner = owner; this.decisions = decisions; this.portfolios = portfolios; this.evidence = evidence; this.metrics = metrics;
        this.clock = clock;
    }
    @Transactional
    public Run createModel(String decisionId, int version, String portfolioSnapshotId) {
        var decision = decisions.requireConfirmed(decisionId, version);
        var portfolio = portfolios.getSnapshot(portfolioSnapshotId);
        if (!portfolio.snapshotId().equals(decision.portfolioSnapshotId()) || !portfolio.baseCurrency().equals(decision.baseCurrency())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        LocalDateTime now = now();
        var run = new Run("run_" + UUID.randomUUID(), decisionId, version, portfolioSnapshotId, portfolio.version(),
                RunStatus.CREATED, null, portfolio.dataMode(), null, null, null, null, null, now, null, null,
                now.plusSeconds(120), 0, 0, 0, 0, 0, 0);
        mapper.insertRun(run, owner.id()); return run;
    }
    @Transactional(readOnly = true)
    public Run get(String id) { return require(mapper.run(id, owner.id())); }
    @Transactional(readOnly = true)
    public Step step(String id, StepKey key) { get(id); return require(mapper.step(id, key)); }
    @Transactional(readOnly = true)
    public List<Event> events(String id) { get(id); return mapper.events(id); }

    @Transactional
    public Run transition(String id, long revision, RunStatus next, Stage stage, String errorCode) {
        Run run = locked(id); if (run.revision() != revision) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        WorkflowStateMachine.require(run.status(), next);
        // Only the later atomic, validated report publisher may materialize these terminal states.
        if (next == RunStatus.COMPLETED || next == RunStatus.PARTIAL) throw new BusinessException(ErrorCode.REPORT_UNAVAILABLE);
        if (next == RunStatus.RUNNING && stage == null) throw new BusinessException(ErrorCode.INVALID_STATE);
        Run updated = copy(run, next, next == RunStatus.QUEUED ? null : stage == null ? run.stage() : stage,
                errorCode, next == RunStatus.RUNNING ? now() : run.startedAt(), next.terminal() ? now() : null);
        EventType type = next == RunStatus.QUEUED ? EventType.RUN_QUEUED : next.terminal() ? EventType.RUN_TERMINATED : null;
        // RUNNING itself is not a fabricated STEP_STARTED; the actual Step transition emits that event.
        if (type == null) updated = withSequence(updated, run.eventSequence());
        saveRun(run, updated);
        if (type != null) event(updated, type, updated.stage());
        return updated;
    }
    @Transactional
    public Step addStep(String id, StepKey key, String inputHash) {
        Run run = locked(id);
        if (run.status() != RunStatus.CREATED && run.status() != RunStatus.QUEUED) throw new BusinessException(ErrorCode.INVALID_STATE);
        if (key == null) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        LocalDateTime now = now();
        var step = new Step("step_" + UUID.randomUUID(), id, key, StepStatus.PENDING, 0, inputHash, null, null, 0, null, now, now);
        mapper.insertStep(step); return step;
    }
    @Transactional
    public Step addNextStep(String id, StepKey key, String inputHash) {
        Run run=locked(id);
        if(run.status()!=RunStatus.RUNNING || key==null || inputHash==null || (run.stage()!=null && key.stage().ordinal()<run.stage().ordinal())) throw new BusinessException(ErrorCode.INVALID_STATE);
        LocalDateTime now=now();
        var step=new Step("step_"+UUID.randomUUID(),id,key,StepStatus.PENDING,0,inputHash,null,null,0,null,now,now);
        mapper.insertStep(step);return step;
    }
    @Transactional
    public Run cancel(String id) {
        Run run=locked(id);
        if(run.status().terminal())return run;
        for(Step step:mapper.steps(id)) {
            if(step.status()!=StepStatus.PENDING && step.status()!=StepStatus.RUNNING)continue;
            Step stopped=new Step(step.stepId(),id,step.stepKey(),StepStatus.SKIPPED,step.attempt(),step.inputHash(),null,null,
                    step.executionVersion()+1,"CANCELLED",step.createdAt(),now());
            if(mapper.updateStep(stopped,step.status(),step.executionVersion())!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        return transition(id,run.revision(),RunStatus.CANCELLED,run.stage(),null);
    }
    @Transactional
    public void expire(String id) {
        Run run=locked(id);LocalDateTime now=now();
        if(run.status().terminal())return;
        if(run.deadlineAt().isAfter(now))throw new BusinessException(ErrorCode.INVALID_STATE);
        for(Step step:mapper.steps(id)) {
            if(step.status()!=StepStatus.PENDING && step.status()!=StepStatus.RUNNING)continue;
            boolean running=step.status()==StepStatus.RUNNING;
            var failed=new Step(step.stepId(),id,step.stepKey(),running?StepStatus.FAILED:StepStatus.SKIPPED,step.attempt(),step.inputHash(),null,null,
                    step.executionVersion()+1,"BUDGET_EXHAUSTED",step.createdAt(),now);
            if(mapper.updateStep(failed,step.status(),step.executionVersion())!=1)throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            if(running){Run advanced=copy(run,run.status(),run.stage(),run.errorCode(),run.startedAt(),null);saveRun(run,advanced);event(advanced,EventType.STEP_FAILED,run.stage());run=advanced;}
        }
        mapper.expireCalls(id,now);
        transition(id,run.revision(),RunStatus.FAILED,run.stage(),"BUDGET_EXHAUSTED");
    }
    @Transactional
    public Step transitionStep(String id, StepKey key, StepStatus next, long token, LocalDateTime leaseUntil, String outputRef, String errorCode) {
        Run run = locked(id);
        if (run.status() != RunStatus.RUNNING && !(run.status() == RunStatus.QUEUED && next == StepStatus.SKIPPED)) throw new BusinessException(ErrorCode.INVALID_STATE);
        Step previous = require(mapper.step(id, key));
        WorkflowStateMachine.require(previous.status(), next);
        if (previous.executionVersion() != token) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        LocalDateTime now = now();
        if (previous.status() == StepStatus.RUNNING) {
            boolean expired = !previous.leaseUntil().isAfter(now);
            if ((next == StepStatus.PENDING) != expired) throw new BusinessException(ErrorCode.INVALID_STATE);
        }
        if (next == StepStatus.PENDING && !run.deadlineAt().isAfter(now)) throw new BusinessException(ErrorCode.INVALID_STATE);
        if (next == StepStatus.RUNNING && (leaseUntil == null || !leaseUntil.isAfter(now) || !run.deadlineAt().isAfter(now) || leaseUntil.isAfter(run.deadlineAt()))) throw new BusinessException(ErrorCode.INVALID_STATE);
        if (next == StepStatus.SUCCEEDED && (outputRef == null || outputRef.isBlank())) throw new BusinessException(ErrorCode.INVALID_STATE);
        int attempt = previous.attempt() + (next == StepStatus.RUNNING ? 1 : 0);
        long version = token + (next == StepStatus.RUNNING || next == StepStatus.PENDING ? 1 : 0);
        var updated = new Step(previous.stepId(), id, key, next, attempt, previous.inputHash(), next == StepStatus.SUCCEEDED || next == StepStatus.FAILED ? outputRef : null,
                next == StepStatus.RUNNING ? leaseUntil : null, version, errorCode, previous.createdAt(), now);
        if (mapper.updateStep(updated, previous.status(), token) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        EventType type = switch (next) { case RUNNING -> EventType.STEP_STARTED; case SUCCEEDED -> EventType.STEP_SUCCEEDED; case FAILED -> EventType.STEP_FAILED; default -> null; };
        if (type != null) {
            Stage newStage = key.stage();
            if (run.stage() != null && newStage.ordinal() < run.stage().ordinal()) throw new BusinessException(ErrorCode.INVALID_STATE);
            Run advanced = copy(run, run.status(), newStage, run.errorCode(), run.startedAt(), null);
            saveRun(run, advanced); event(advanced, type, newStage);
        }
        return updated;
    }
    @Transactional
    public void linkSnapshot(String id, String snapshotId) {
        Run run = locked(id); requireMutable(run);
        var snapshot = evidence.snapshot(snapshotId);
        if (!run.portfolioSnapshotId().equals(snapshot.portfolioRef().snapshotId()) || run.portfolioVersion() != snapshot.portfolioRef().version()
                || run.dataMode() != snapshot.dataMode()) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        mapper.linkSnapshot(id, snapshotId, run.portfolioSnapshotId());
    }
    @Transactional
    public void linkBundle(String id, String bundleId) {
        Run run = locked(id); requireMutable(run);
        var bundle = metrics.get(bundleId);
        var snapshot = evidence.snapshot(bundle.calculationSnapshotId());
        if (!run.portfolioSnapshotId().equals(snapshot.portfolioRef().snapshotId()) || run.dataMode() != bundle.dataMode()) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        mapper.linkBundle(id, bundleId, snapshot.snapshotId());
    }
    private Run locked(String id) { return require(mapper.lockRun(id, owner.id())); }
    private static void requireMutable(Run run) { if (run.status().terminal()) throw new BusinessException(ErrorCode.INVALID_STATE); }
    private void saveRun(Run previous, Run next) { if (mapper.updateRun(next, owner.id(), previous.revision()) != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT); }
    private void event(Run run, EventType type, Stage stage) {
        mapper.insertEvent(new Event(run.runId() + ":" + run.eventSequence(), run.runId(), run.eventSequence(), type, stage, run.status(), now(), type.name()));
    }
    private static Run copy(Run r, RunStatus status, Stage stage, String error, LocalDateTime started, LocalDateTime finished) {
        return new Run(r.runId(), r.decisionId(), r.decisionVersion(), r.portfolioSnapshotId(), r.portfolioVersion(), status, stage,
                r.dataMode(), r.dataQuality(), r.researchSnapshotId(), r.snapshotHash(), r.reportId(), error, r.createdAt(), started,
                finished, r.deadlineAt(), r.dataAttempts(), r.modelAttempts(), r.schemaRepairs(), r.retryCount(), r.revision() + 1, r.eventSequence() + 1);
    }
    private static Run withSequence(Run r, long sequence) { return new Run(r.runId(), r.decisionId(), r.decisionVersion(), r.portfolioSnapshotId(), r.portfolioVersion(), r.status(), r.stage(), r.dataMode(), r.dataQuality(), r.researchSnapshotId(), r.snapshotHash(), r.reportId(), r.errorCode(), r.createdAt(), r.startedAt(), r.finishedAt(), r.deadlineAt(), r.dataAttempts(), r.modelAttempts(), r.schemaRepairs(), r.retryCount(), r.revision(), sequence); }
    private static <T> T require(T value) { if (value == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); return value; }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); }
}
