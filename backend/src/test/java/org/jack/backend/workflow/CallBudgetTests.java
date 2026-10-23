package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.common.exception.*;
import org.jack.backend.portfolio.PortfolioDtos;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.CallBudgetData.*;
import static org.jack.backend.workflow.WorkflowData.*;

@Import(CallBudgetTests.TimeConfiguration.class)
class CallBudgetTests extends WorkflowIntegrationSupport {
    @Autowired WorkflowClaimService claims;
    @Autowired CallBudgetService budgets;
    @Autowired BudgetedCalls calls;
    @Autowired MutableClock clock;
    @BeforeEach void resetTime(){clock.instant=Instant.parse("2026-10-23T13:00:00Z");}
    Request data(String id){return new Request(Kind.DATA,Purpose.STANDARD,id,LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-05"));}
    void exhausted(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXHAUSTED));}
    @Test void concurrentDataReservationsStopAtEightAndDeniedCallsNeverInvokeOperation() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);var invoked=new AtomicInteger();
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<12;i++)tasks.add(pool.submit(()->{gate.await();try{calls.execute(c,data(GOLD),permit->{invoked.incrementAndGet();assertThat(permit.timeoutMillis()).isEqualTo(8000);return "ok";});return true;}catch(BusinessException e){assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BUDGET_EXHAUSTED);return false;}}));
            gate.countDown();int accepted=0;for(var f:tasks)if(f.get(15,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(8);assertThat(invoked).hasValue(8);assertThat(store.get(r.runId()).dataAttempts()).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE run_id=? AND status='SUCCEEDED'",Integer.class,r.runId())).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_data_windows WHERE run_id=?",Integer.class,r.runId())).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void fourStandardModelsAndOneGlobalRepairShareTotalFiveAttempts() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();
        for(int i=0;i<4;i++)budgets.reserve(c,Request.model(Purpose.STANDARD));
        exhausted(()->budgets.reserve(c,Request.model(Purpose.STANDARD)));
        var repair=budgets.reserve(c,Request.model(Purpose.SCHEMA_REPAIR));
        assertThat(repair.timeoutMillis()).isEqualTo(30000);assertThat(repair.remaining().remainingModelAttempts()).isZero();assertThat(repair.remaining().remainingSchemaRepairs()).isZero();
        exhausted(()->budgets.reserve(c,Request.model(Purpose.SCHEMA_REPAIR)));
        assertThat(store.get(r.runId()).modelAttempts()).isEqualTo(5);assertThat(store.get(r.runId()).schemaRepairs()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE research_runs SET model_attempts=6 WHERE run_id=?",r.runId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void retriesShareGlobalTwoAcrossDataAndModelsAndStillConsumeKindQuota() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();
        var q=data(GOLD);budgets.reserve(c,new Request(Kind.DATA,Purpose.RETRY,q.instrumentId(),q.start(),q.end()));
        var second=budgets.reserve(c,Request.model(Purpose.RETRY));assertThat(second.remaining().remainingRetries()).isZero();
        exhausted(()->budgets.reserve(c,Request.model(Purpose.RETRY)));
        assertThat(store.get(r.runId()).dataAttempts()).isEqualTo(1);assertThat(store.get(r.runId()).modelAttempts()).isEqualTo(1);assertThat(store.get(r.runId()).retryCount()).isEqualTo(2);
    }
    @Test void globalRepairAndAttemptsSurviveReclaimAndLateAttemptFactCannotChangeCurrentStep() throws Exception {
        var r=queued(fixture());var old=claims.claimNext();var repair=budgets.reserve(old,Request.model(Purpose.SCHEMA_REPAIR));
        clock.advance(45_000);var current=claims.claimNext();
        exhausted(()->budgets.reserve(current,Request.model(Purpose.SCHEMA_REPAIR)));
        var next=budgets.reserve(current,Request.model(Purpose.STANDARD));assertThat(next.remaining().remainingModelAttempts()).isEqualTo(3);
        assertThat(budgets.finish(repair,null)).isFalse();assertThat(budgets.finish(repair,null)).isFalse();
        assertThat(store.step(r.runId(),StepKey.PLAN)).isEqualTo(current.step());
        assertThat(store.get(r.runId()).modelAttempts()).isEqualTo(2);assertThat(store.get(r.runId()).schemaRepairs()).isEqualTo(1);
    }
    @Test void queueTimeDeadlineAndRemainingBudgetReachHandlerAndCallWithoutReset() throws Exception {
        var r=queued(fixture());clock.advance(118_000);var c=claims.claimNext();
        assertThat(c.budget().remainingMillis()).isEqualTo(2000);assertThat(c.step().leaseUntil()).isEqualTo(r.deadlineAt());
        var p=budgets.reserve(c,data(GOLD));assertThat(p.timeoutMillis()).isEqualTo(2000);assertThat(p.remaining().remainingDataAttempts()).isEqualTo(7);
        clock.advance(2000);var invoked=new AtomicInteger();
        exhausted(()->calls.execute(c,data(GOLD),permit->{invoked.incrementAndGet();return "forbidden";}));assertThat(invoked).hasValue(0);
        assertThat(budgets.finish(p,null)).isFalse();
        assertThat(claims.claimNext()).isNull();var ended=store.get(r.runId());assertThat(ended.status()).isEqualTo(RunStatus.FAILED);assertThat(ended.errorCode()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(store.step(r.runId(),StepKey.PLAN).status()).isEqualTo(StepStatus.FAILED);
        assertThat(store.step(r.runId(),StepKey.PLAN).executionVersion()).isGreaterThan(c.step().executionVersion());
        assertThat(jdbc.queryForObject("SELECT status FROM tool_calls WHERE call_id=?",String.class,p.callId())).isEqualTo("TIMED_OUT");
        assertThat(ended.dataAttempts()).isEqualTo(1);assertThat(RunDtos.remaining(ended,clock).remainingMillis()).isZero();
    }
    @Test void exceptionAndOverdueSuccessAreMeteredWithoutReturningLateValue() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();
        assertThatThrownBy(()->calls.execute(c,data(GOLD),permit->{throw new BusinessException(ErrorCode.UPSTREAM_ERROR);})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->calls.execute(c,data(GOLD),permit->{clock.advance(permit.timeoutMillis());return "late";}))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
        assertThat(store.get(r.runId()).dataAttempts()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM tool_calls WHERE run_id=?",String.class,r.runId())).containsExactlyInAnyOrder("FAILED","TIMED_OUT");
        assertThat(jdbc.queryForList("SELECT error_code FROM tool_calls WHERE run_id=?",String.class,r.runId())).containsExactlyInAnyOrder("UPSTREAM_ERROR","UPSTREAM_TIMEOUT");
    }
    @Test void changedWindowCashAndExpiredTokensCannotReserveOrConsumeQuota() throws Exception {
        var r=queued(fixture());var c=claims.claimNext();var q=data(GOLD);budgets.reserve(c,q);
        assertThatThrownBy(()->budgets.reserve(c,new Request(Kind.DATA,Purpose.STANDARD,GOLD,q.start(),q.end().plusDays(1))))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SNAPSHOT_MISMATCH));
        assertThatThrownBy(()->budgets.reserve(c,data(CASH))).isInstanceOf(BusinessException.class);
        clock.advance(45_000);assertThatThrownBy(()->budgets.reserve(c,q)).isInstanceOf(BusinessException.class);
        var reclaimed=claims.claimNext();assertThat(reclaimed.step().attempt()).isEqualTo(2);
        assertThatThrownBy(()->budgets.reserve(c,q)).isInstanceOf(BusinessException.class);
        assertThat(budgets.reserve(reclaimed,q).remaining().remainingDataAttempts()).isEqualTo(6);assertThat(store.get(r.runId()).dataAttempts()).isEqualTo(2);
    }
    @Test void threeNonCashAssetsAreAllowedWithOneWindowEach() throws Exception {
        var input=(tools.jackson.databind.node.ObjectNode)json.readTree(getClass().getResourceAsStream("/portfolio-create.json"));
        var positions=json.createArrayNode();
        for(String id:List.of(GOLD,SILVER,"STOCK:SYNTHETIC:DEMO_STOCK:USD",CASH))positions.add(json.createObjectNode().put("instrumentId",id).put("quoteCurrency","USD").put("weight","0.25"));
        input.set("positions",positions);var portfolio=portfolios.create(json.treeToValue(input,PortfolioDtos.Create.class));portfolioIds.add(portfolio.portfolioId());
        var r=queued(fixture(portfolio));var c=claims.claimNext();for(var p:portfolio.positions())if(!p.instrumentId().equals(CASH))budgets.reserve(c,data(p.instrumentId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_data_windows WHERE run_id=?",Integer.class,r.runId())).isEqualTo(3);
        assertThat(store.get(r.runId()).dataAttempts()).isEqualTo(3);
    }
    @TestConfiguration static class TimeConfiguration{@Bean @Primary MutableClock controlledClock(){return new MutableClock();}}
    static class MutableClock extends Clock {
        volatile Instant instant=Instant.parse("2026-10-23T13:00:00Z");
        void advance(long millis){instant=instant.plusMillis(millis);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(instant,zone);}
        @Override public Instant instant(){return instant;}
    }
}
