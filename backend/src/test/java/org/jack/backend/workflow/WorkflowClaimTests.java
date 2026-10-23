package org.jack.backend.workflow;

import org.jack.backend.support.WorkflowIntegrationSupport;
import org.jack.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

class WorkflowClaimTests extends WorkflowIntegrationSupport {
    @Autowired WorkflowClaimService claims;
    @Autowired WorkflowHandler handler;
    @Test void parallelScansHaveOnlyOneLiveClaimAndMinimumHandlerUsesFixedInputs() throws Exception {
        var r=queued(fixture());var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try {
            List<Future<WorkflowClaimService.Claim>> results=new ArrayList<>();
            for(int i=0;i<4;i++) results.add(pool.submit(()->{gate.await();return claims.claimNext();}));gate.countDown();
            var claimed=new ArrayList<WorkflowClaimService.Claim>();for(var f:results){var c=f.get(10,TimeUnit.SECONDS);if(c!=null)claimed.add(c);}
            assertThat(claimed).hasSize(1);var c=claimed.get(0);assertThat(c.run().runId()).isEqualTo(r.runId());
            assertThat(c.step().attempt()).isEqualTo(1);assertThat(c.step().executionVersion()).isEqualTo(1);
            assertThat(claims.claimNext()).isNull();assertThat(handler.execute(c).output()).containsEntry("portfolioSnapshotId",r.portfolioSnapshotId()).containsEntry("modelSteps","DISABLED");
        } finally{pool.shutdownNow();}
    }
    @Test void expiredLeaseReclaimInvalidatesOldTokenAndCannotCompleteLate() throws Exception {
        var r=queued(fixture());var old=claims.claimNext();assertThat(old).isNotNull();
        jdbc.update("UPDATE run_steps SET lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE step_id=?",old.step().stepId());
        assertThatThrownBy(()->store.transitionStep(r.runId(),StepKey.PLAN,StepStatus.SUCCEEDED,old.step().executionVersion(),null,"late",null)).isInstanceOf(BusinessException.class);
        var next=claims.claimNext();assertThat(next.step().executionVersion()).isGreaterThan(old.step().executionVersion());assertThat(next.step().attempt()).isEqualTo(2);
        assertThatThrownBy(()->store.transitionStep(r.runId(),StepKey.PLAN,StepStatus.SUCCEEDED,old.step().executionVersion(),null,"late",null)).isInstanceOf(BusinessException.class);
    }
    @Test void queueTimeConsumesDeadlineAndExpiredQueueNeverStarts() throws Exception {
        var r=queued(fixture());jdbc.update("UPDATE research_runs SET created_at=UTC_TIMESTAMP(3)-INTERVAL 121 SECOND, deadline_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE run_id=?",r.runId());
        assertThat(claims.claimNext()).isNull();assertThat(store.get(r.runId()).status()).isEqualTo(RunStatus.FAILED);
        assertThat(store.get(r.runId()).errorCode()).isEqualTo("BUDGET_EXHAUSTED");assertThat(store.step(r.runId(),StepKey.PLAN).attempt()).isZero();
    }
}
