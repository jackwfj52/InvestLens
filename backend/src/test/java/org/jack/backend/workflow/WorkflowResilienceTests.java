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
}
