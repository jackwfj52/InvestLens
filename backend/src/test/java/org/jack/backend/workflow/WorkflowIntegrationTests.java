package org.jack.backend.workflow;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.jack.backend.common.exception.*;
import org.jack.backend.decision.*;
import org.jack.backend.support.CalculationIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Import(WorkflowIntegrationTests.FaultConfiguration.class)
class WorkflowIntegrationTests extends CalculationIntegrationSupport {
    @Autowired WorkflowStore store;
    @Autowired WorkflowMapper mapper;
    @Autowired DecisionService decisions;
    @Autowired EventFault fault;
    final List<String> runIds = new ArrayList<>(), decisionIds = new ArrayList<>();
    record Fixture(Run run, org.jack.backend.portfolio.PortfolioDtos.Snapshot portfolio) { }
    Fixture fixture() throws Exception {
        var p = portfolio(); var draft = (ObjectNode) json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        draft.put("inputStatus", "CONFIRMED"); ((ObjectNode) draft.get("proposedDecision")).put("portfolioSnapshotId", p.snapshotId());
        var d = decisions.create(json.treeToValue(draft, DecisionDtos.Draft.class)); decisionIds.add(d.decisionId());
        var run = store.createModel(d.decisionId(), d.version(), p.snapshotId()); runIds.add(run.runId()); return new Fixture(run, p);
    }
    Run running(Run run) { var queued = store.transition(run.runId(), run.revision(), RunStatus.QUEUED, null, null); return store.transition(run.runId(), queued.revision(), RunStatus.RUNNING, Stage.PLAN, null); }
    @AfterEach void cleanWorkflowRows() {
        fault.fail.set(false);
        runIds.forEach(id -> {
            jdbc.update("DELETE FROM run_metric_bundles WHERE run_id = ?", id); jdbc.update("DELETE FROM run_calculation_inputs WHERE run_id = ?", id);
            jdbc.update("DELETE FROM run_events WHERE run_id = ?", id); jdbc.update("DELETE FROM run_steps WHERE run_id = ?", id); jdbc.update("DELETE FROM research_runs WHERE run_id = ?", id);
        });
        decisionIds.forEach(id -> { jdbc.update("DELETE FROM decision_versions WHERE decision_id = ?", id); jdbc.update("DELETE FROM decisions WHERE decision_id = ?", id); });
    }
    @Test void modelHasFrozenInputAndSeparateStateStageQualityAndPersistedCounters() throws Exception {
        var f = fixture(); Run r = f.run();
        assertThat(store.get(r.runId())).isEqualTo(r);
        assertThat(r.status()).isEqualTo(RunStatus.CREATED); assertThat(r.stage()).isNull(); assertThat(r.dataQuality()).isNull();
        assertThat(r.researchSnapshotId()).isNull(); assertThat(r.reportId()).isNull(); assertThat(r.eventSequence()).isZero();
        assertThat(Duration.between(r.createdAt(), r.deadlineAt()).toSeconds()).isEqualTo(120);
        org.jack.backend.portfolio.PortfolioDtos.Snapshot revised = portfolios.revise(f.portfolio().portfolioId(),
                new org.jack.backend.portfolio.PortfolioDtos.Revision("1.0.0", 1, f.portfolio().positions(), "2026-10-23T02:00:00.000Z"));
        assertThat(store.get(r.runId()).portfolioSnapshotId()).isNotEqualTo(revised.snapshotId());
    }
    @Test void illegalAndTerminalTransitionsAreRejectedInServiceAndDatabase() throws Exception {
        Run r = fixture().run();
        assertThatThrownBy(() -> store.transition(r.runId(), 0, RunStatus.COMPLETED, Stage.VALIDATE, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE research_runs SET status = 'RUNNING' WHERE run_id = ?", r.runId())).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_run_started");
        var queued = store.transition(r.runId(), 0, RunStatus.QUEUED, null, null);
        var cancelled = store.transition(r.runId(), queued.revision(), RunStatus.CANCELLED, null, "RUN_CANCELLED");
        assertThat(cancelled.finishedAt()).isNotNull(); assertThat(cancelled.stage()).isNull();
        assertThatThrownBy(() -> store.transition(r.runId(), cancelled.revision(), RunStatus.RUNNING, Stage.PLAN, null)).isInstanceOf(BusinessException.class);
        assertThat(mapper.updateRun(r, "local", cancelled.revision())).isZero();
        assertThat(store.get(r.runId())).isEqualTo(cancelled);
        assertThat(store.events(r.runId())).extracting(Event::type).containsExactly(EventType.RUN_QUEUED, EventType.RUN_TERMINATED);
    }
    @Test void completedAndPartialRequireTheFutureValidatedReportPublisher() throws Exception {
        Run r = running(fixture().run());
        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.PARTIAL)) {
            assertThatThrownBy(() -> store.transition(r.runId(), r.revision(), status, Stage.VALIDATE, null)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REPORT_UNAVAILABLE));
            assertThatThrownBy(() -> jdbc.update("UPDATE research_runs SET status = ?, finished_at = UTC_TIMESTAMP(3) WHERE run_id = ?", status.name(), r.runId())).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_run_report");
        }
    }
    @Test void stepsHaveUniqueKeysTokensLeasesAttemptsAndRealOutputReferences() throws Exception {
        var f = fixture(); Run r = f.run();
        store.addStep(r.runId(), StepKey.PLAN, null);
        assertThatThrownBy(() -> store.addStep(r.runId(), StepKey.PLAN, null)).isInstanceOf(DataIntegrityViolationException.class);
        running(r);
        Step started = store.transitionStep(r.runId(), StepKey.PLAN, StepStatus.RUNNING, 0, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), null, null);
        assertThat(started.attempt()).isEqualTo(1); assertThat(started.executionVersion()).isEqualTo(1); assertThat(started.leaseUntil()).isNotNull();
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.PLAN, StepStatus.SUCCEEDED, 1, null, null, null)).isInstanceOf(BusinessException.class);
        // A real persisted output identifier demonstrates the output-ref field, not a fabricated report.
        var output = calculate(snapshot(f.portfolio(), List.of(history(31, "PAYLOAD_ALLOWED"))));
        Step finished = store.transitionStep(r.runId(), StepKey.PLAN, StepStatus.SUCCEEDED, 1, null, output.bundleId(), null);
        assertThat(finished.outputRef()).isEqualTo(output.bundleId()); assertThat(finished.leaseUntil()).isNull();
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.PLAN, StepStatus.PENDING, 1, null, null, null)).isInstanceOf(BusinessException.class);
        assertThat(mapper.updateStep(started, StepStatus.SUCCEEDED, finished.executionVersion())).isZero();
        assertThat(store.step(r.runId(), StepKey.PLAN)).isEqualTo(finished);
        assertThat(store.events(r.runId())).extracting(Event::sequence).containsExactly(1L, 2L, 3L);
    }
    @Test void expiredStepRecoveryFencesOldExecutionWithoutResettingAttempt() throws Exception {
        Run r = fixture().run(); store.addStep(r.runId(), StepKey.RESEARCH, null); running(r);
        Step active = store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.RUNNING, 0, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), null, null);
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.PENDING, 1, null, null, null)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE run_steps SET lease_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE step_id = ?", active.stepId());
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.SUCCEEDED, 1, null, "late_output", null)).isInstanceOf(BusinessException.class);
        Step pending = store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.PENDING, 1, null, null, "LEASE_EXPIRED");
        assertThat(pending.executionVersion()).isEqualTo(2); assertThat(pending.attempt()).isEqualTo(1);
        Step next = store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.RUNNING, 2, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), null, null);
        assertThat(next.attempt()).isEqualTo(2); assertThat(next.executionVersion()).isEqualTo(3);
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.RESEARCH, StepStatus.FAILED, 1, null, null, "LATE")).isInstanceOf(BusinessException.class);
    }
    @Test void eventSequencesAreUniqueAppendOnlyAndRolledBackWithState() throws Exception {
        Run r = fixture().run(); fault.fail.set(true);
        assertThatThrownBy(() -> store.transition(r.runId(), 0, RunStatus.QUEUED, null, null)).isInstanceOf(RuntimeException.class);
        assertThat(store.get(r.runId()).status()).isEqualTo(RunStatus.CREATED); assertThat(store.events(r.runId())).isEmpty();
        store.transition(r.runId(), 0, RunStatus.QUEUED, null, null);
        var e = store.events(r.runId()).get(0);
        assertThat(e.eventId()).isEqualTo(r.runId() + ":1");
        assertThatThrownBy(() -> mapper.insertEvent(new Event("another_event", r.runId(), 1, e.type(), e.stage(), e.status(), e.createdAt(), "test"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.events(r.runId()).get(0)).isEqualTo(e);
    }
    @Test void competingRunTransitionsHaveOnlyOneWinner() throws Exception {
        Run r = fixture().run(); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { start.await(); try { store.transition(r.runId(), 0, RunStatus.QUEUED, null, null); return true; } catch (BusinessException conflict) { return false; } }));
            start.countDown(); int winners = 0; for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1); assertThat(store.events(r.runId())).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void snapshotAndMetricLinksRequireSamePortfolioAndRetainImmutableSourceObjects() throws Exception {
        var f = fixture(); var s = snapshot(f.portfolio(), List.of(history(31, "PAYLOAD_ALLOWED"))); var b = calculate(s);
        store.linkSnapshot(f.run().runId(), s.snapshotId()); store.linkBundle(f.run().runId(), b.bundleId());
        assertThat(metrics.get(b.bundleId())).isEqualTo(b); assertThat(evidence.snapshot(s.snapshotId())).isEqualTo(s);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_metric_bundles WHERE run_id = ?", Integer.class, f.run().runId())).isEqualTo(1);
        var other = snapshot(portfolio(), List.of(history(31, "PAYLOAD_ALLOWED")));
        assertThatThrownBy(() -> store.linkSnapshot(f.run().runId(), other.snapshotId())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_MISMATCH));
        assertThatThrownBy(() -> mapper.linkSnapshot(f.run().runId(), other.snapshotId(), f.portfolio().snapshotId())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void terminalRunsRejectStepWritesAndForeignOwnersCannotReadModel() throws Exception {
        Run r = fixture().run(); store.addStep(r.runId(), StepKey.QUANT, null); Run active = running(r);
        store.transition(r.runId(), active.revision(), RunStatus.FAILED, null, "TEST_FAILURE");
        assertThatThrownBy(() -> store.transitionStep(r.runId(), StepKey.QUANT, StepStatus.RUNNING, 0, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.get("unknown_run")).isInstanceOf(BusinessException.class);
        assertThat(mapper.run(r.runId(), "another_owner")).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE research_runs SET decision_version = 99 WHERE run_id = ?", r.runId())).isInstanceOf(RuntimeException.class);
    }
    @TestConfiguration static class FaultConfiguration { @Bean EventFault eventFault() { return new EventFault(); } }
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class EventFault implements Interceptor {
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement) invocation.getArgs()[0]).getId().equals(WorkflowMapper.class.getName() + ".insertEvent") && fail.getAndSet(false)) throw new IllegalStateException("Synthetic event persistence fault");
            return invocation.proceed();
        }
    }
}
