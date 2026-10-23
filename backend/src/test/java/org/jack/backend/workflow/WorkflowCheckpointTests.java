package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Import(WorkflowIntegrationTests.FaultConfiguration.class)
class WorkflowCheckpointTests extends WorkflowIntegrationSupport {
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowCheckpointService checkpoints;
    @Autowired WorkflowHandler handler;
    @Autowired WorkflowIntegrationTests.EventFault fault;
    @Test void outputStateEventAndNextStepCommitTogetherAndSurviveReread() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();checkpoints.save(c,handler.execute(c));
        var step=store.step(r.runId(),StepKey.PLAN);assertThat(step.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(json.readTree(workflowMapper.output(step.outputRef())).get("portfolioSnapshotId").asString()).isEqualTo(r.portfolioSnapshotId());
        assertThat(store.step(r.runId(),StepKey.RESEARCH).inputHash()).startsWith("sha256:");
        assertThat(store.events(r.runId())).extracting(Event::type).containsExactly(EventType.RUN_QUEUED,EventType.STEP_STARTED,EventType.STEP_SUCCEEDED);
        assertThatThrownBy(()->checkpoints.save(c,handler.execute(c))).isInstanceOf(BusinessException.class);
    }
    @Test void eventFaultRollsBackOutputSuccessAndNextStep() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();fault.fail.set(true);
        assertThatThrownBy(()->checkpoints.save(c,handler.execute(c))).isInstanceOf(RuntimeException.class);
        assertThat(store.step(r.runId(),StepKey.PLAN).status()).isEqualTo(StepStatus.RUNNING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_outputs WHERE run_id=?",Integer.class,r.runId())).isZero();
        assertThat(workflowMapper.step(r.runId(),StepKey.RESEARCH)).isNull();assertThat(store.events(r.runId())).hasSize(2);
    }
    @Test void boundedExecutorDoesNotClaimBeyondCapacityAndInterruptedWorkHasNoSuccessWithoutOutput() throws Exception {
        queued(fixture());queued(fixture());queued(fixture());var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        WorkflowHandler blocking=c->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return handler.execute(c);};
        var scheduler=new WorkflowScheduler(claims,blocking,checkpoints);
        try {
            assertThat(scheduler.submitOne()).isTrue();assertThat(scheduler.submitOne()).isTrue();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.submitOne()).isFalse();assertThat(scheduler.inFlight()).isEqualTo(2);
            assertThat(runIds.stream().filter(id->store.get(id).status()==RunStatus.QUEUED)).hasSize(1);
            scheduler.stop();release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(scheduler.inFlight()>0 && System.nanoTime()<until)Thread.sleep(10);
            assertThat(scheduler.inFlight()).isZero();
            for(String id:runIds){assertThat(store.step(id,StepKey.PLAN).status()).isNotEqualTo(StepStatus.SUCCEEDED);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_outputs WHERE run_id=?",Integer.class,id)).isZero();}
        }finally{release.countDown();scheduler.stop();}
    }
}
