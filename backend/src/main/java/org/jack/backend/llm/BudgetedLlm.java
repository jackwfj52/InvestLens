package org.jack.backend.llm;

import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import static org.jack.backend.workflow.CallBudgetData.*;

@Service
public class BudgetedLlm {
    private final LlmClient client;
    private final BudgetedCalls calls;
    private final CallBudgetService budgets;
    private final java.util.concurrent.Semaphore slots=new java.util.concurrent.Semaphore(2,true);
    public BudgetedLlm(LlmClient client,BudgetedCalls calls,CallBudgetService budgets){this.client=client;this.calls=calls;this.budgets=budgets;}
    public LlmClient.Reply complete(WorkflowClaimService.Claim claim,Purpose purpose,LlmClient.Request request) {
        return calls.executeWithRetry(claim,Request.model(purpose),permit->{
            boolean acquired=false;
            try {
                acquired=slots.tryAcquire(budgets.dispatchTimeout(claim,permit),java.util.concurrent.TimeUnit.MILLISECONDS);
                if(!acquired)throw new org.jack.backend.common.exception.BusinessException(org.jack.backend.common.exception.ErrorCode.UPSTREAM_TIMEOUT);
                var reply=client.complete(request,budgets.dispatchTimeout(claim,permit));budgets.recordUsage(permit,reply);return reply;
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new org.jack.backend.common.exception.BusinessException(org.jack.backend.common.exception.ErrorCode.UPSTREAM_TIMEOUT);}
            finally{if(acquired)slots.release();}
        });
    }
}
