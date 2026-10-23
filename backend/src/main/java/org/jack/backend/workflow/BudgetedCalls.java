package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import org.springframework.stereotype.Service;
import java.util.function.Function;
import static org.jack.backend.workflow.CallBudgetData.*;

/** One invocation is one persisted attempt. No implicit transport or layered retries. */
@Service
public class BudgetedCalls {
    private final CallBudgetService budgets;
    public BudgetedCalls(CallBudgetService budgets){this.budgets=budgets;}
    public <T> T execute(WorkflowClaimService.Claim claim,Request request,Function<Permit,T> operation) {
        Permit permit=budgets.reserve(claim,request);
        T result;
        try{budgets.beforeDispatch(claim,permit);result=operation.apply(permit);}
        catch(RuntimeException error){budgets.finish(permit,error instanceof BusinessException b?b.getErrorCode().name():"UPSTREAM_ERROR");throw error;}
        if(!budgets.finish(permit,null))throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);
        budgets.requireActive(claim);
        return result;
    }
    public <T> T executeWithRetry(WorkflowClaimService.Claim claim,Request request,Function<Permit,T> operation) {
        return executeWithRetry(claim,request,operation,Thread::sleep);
    }
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public <T> T executeWithRetry(WorkflowClaimService.Claim claim,Request request,Function<Permit,T> operation,Sleeper sleeper) {
        Request attempt=request;
        for(int retry=0;;retry++) {
            try{return execute(claim,attempt,operation);}
            catch(UpstreamFailure failure) {
                if(!failure.retryable() || retry>=2)throw failure;
                long delay=Math.max(failure.retryAfterMillis(),250L*(1L<<retry));
                if(delay>120_000)throw new BusinessException(ErrorCode.BUDGET_EXHAUSTED);
                budgets.beforeBackoff(claim,delay);
                try{sleeper.sleep(delay);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT);}
                attempt=new Request(request.kind(),Purpose.RETRY,request.instrumentId(),request.start(),request.end());
            }
        }
    }
}
