package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.common.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.CallBudgetData.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Import(CallBudgetTests.TimeConfiguration.class)
class WorkflowResilienceTests extends WorkflowIntegrationSupport {
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowCheckpointService checkpoints;
    @Autowired WorkflowHandler handler;
    @Autowired CallBudgetService budgets;
    @Autowired BudgetedCalls calls;
    @Autowired CallBudgetTests.MutableClock clock;
    @BeforeEach void reset(){clock.instant=java.time.Instant.parse("2026-10-23T13:00:00Z");}
    @Test void cancellationIsIdempotentAndFencesDispatchedCallsAndWrites() throws Exception {
        var run=queued(fixture());var claim=claims.claimNext();var permit=budgets.reserve(claim,Request.model(Purpose.STANDARD));
        var cancelled=store.cancel(run.runId());assertThat(store.cancel(run.runId())).isEqualTo(cancelled);
        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);assertThat(claims.claimNext()).isNull();
        assertThat(store.step(run.runId(),StepKey.PLAN).executionVersion()).isGreaterThan(claim.step().executionVersion());
        assertThat(budgets.finish(permit,null)).isTrue();
        assertThatThrownBy(()->checkpoints.save(claim,handler.execute(claim))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->budgets.reserve(claim,Request.model(Purpose.STANDARD))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_outputs WHERE run_id=?",Integer.class,run.runId())).isZero();
    }
    @Test void cancellationRacesCheckpointAtomically() throws Exception {
        var run=queued(fixture());var claim=claims.claimNext();var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var save=pool.submit(()->{gate.await();try{checkpoints.save(claim,handler.execute(claim));}catch(BusinessException stale){}return true;});
            var cancel=pool.submit(()->{gate.await();return store.cancel(run.runId());});gate.countDown();save.get(5,TimeUnit.SECONDS);cancel.get(5,TimeUnit.SECONDS);
            assertThat(store.get(run.runId()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThat(workflowMapper.steps(run.runId())).noneMatch(step->step.status()==StepStatus.RUNNING || step.status()==StepStatus.PENDING);
        }finally{pool.shutdownNow();}
    }
    @Test void retriesRespectAfterGlobalQuotaAndDeadline() throws Exception {
        var run=queued(fixture());var claim=claims.claimNext();var count=new AtomicInteger();
        String result=calls.executeWithRetry(claim,Request.model(Purpose.STANDARD),permit->{
            if(count.incrementAndGet()<3)throw new UpstreamFailure(ErrorCode.RATE_LIMITED,true,2000);return "ok";
        },clock::advance);assertThat(result).isEqualTo("ok");
        assertThat(count).hasValue(3);assertThat(store.get(run.runId()).retryCount()).isEqualTo(2);
        assertThat(store.get(run.runId()).modelAttempts()).isEqualTo(3);
        assertThatThrownBy(()->calls.executeWithRetry(claim,Request.model(Purpose.STANDARD),permit->{throw new UpstreamFailure(ErrorCode.RATE_LIMITED,true,1);},clock::advance)).isInstanceOf(BusinessException.class);
    }
    @Test void authNeverRetriesAndOversizedAfterNeverSleeps() throws Exception {
        queued(fixture());var claim=claims.claimNext();var count=new AtomicInteger();
        assertThatThrownBy(()->calls.executeWithRetry(claim,Request.model(Purpose.STANDARD),permit->{count.incrementAndGet();throw new UpstreamFailure(ErrorCode.PROVIDER_NOT_CONFIGURED,false,0);},millis->{throw new AssertionError();})).isInstanceOf(BusinessException.class);
        assertThat(count).hasValue(1);
        assertThatThrownBy(()->calls.executeWithRetry(claim,Request.model(Purpose.STANDARD),permit->{throw new UpstreamFailure(ErrorCode.RATE_LIMITED,true,120001);},millis->{throw new AssertionError();})).isInstanceOf(BusinessException.class);
    }
    @Test void quantCheckpointReusedAfterReclaimAndBudgetNeverResets() throws Exception {
        var run=queued(fixture());
        for(int index=0;index<3;index++){var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));}
        var quant=store.step(run.runId(),StepKey.QUANT);var old=claims.claimNext();budgets.reserve(old,Request.model(Purpose.STANDARD));
        clock.advance(45000);var recovered=claims.claimNext();assertThat(recovered.step().stepKey()).isEqualTo(StepKey.BULL);
        assertThat(recovered.step().executionVersion()).isGreaterThan(old.step().executionVersion());
        assertThat(store.step(run.runId(),StepKey.QUANT)).isEqualTo(quant);assertThat(recovered.run().modelAttempts()).isEqualTo(1);
        assertThat(recovered.run().deadlineAt()).isEqualTo(run.deadlineAt());
        assertThatThrownBy(()->checkpoints.save(old,handler.execute(old))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_outputs WHERE run_id=?",Integer.class,run.runId())).isEqualTo(3);
    }
    @Test void corruptedOutputStopsRecovery() throws Exception {
        var run=queued(fixture());var claim=claims.claimNext();checkpoints.save(claim,handler.execute(claim));
        jdbc.update("UPDATE step_outputs SET output_hash=? WHERE run_id=?","sha256:"+"0".repeat(64),run.runId());
        assertThat(claims.claimNext()).isNull();assertThat(store.get(run.runId()).errorCode()).isEqualTo("SNAPSHOT_MISMATCH");
    }
    @Test void cancelApiReturnsTerminalStateAndUnknownRunIs404() throws Exception {
        var run=queued(fixture());var http=java.net.http.HttpClient.newHttpClient();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/runs/"+run.runId()+"/cancel")).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        for(int index=0;index<2;index++) {
            var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);assertThat(json.readTree(response.body()).path("data").path("status").asString()).isEqualTo("CANCELLED");
        }
        var missing=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/runs/run_missing/cancel")).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(missing,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }
}
