package org.jack.backend.llm;

import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import static org.jack.backend.workflow.CallBudgetData.*;

@Service
public class BudgetedLlm {
    private final LlmClient client;
    private final BudgetedCalls calls;
    private final CallBudgetService budgets;
    public BudgetedLlm(LlmClient client,BudgetedCalls calls,CallBudgetService budgets){this.client=client;this.calls=calls;this.budgets=budgets;}
    public LlmClient.Reply complete(WorkflowClaimService.Claim claim,Purpose purpose,LlmClient.Request request) {
        return calls.executeWithRetry(claim,Request.model(purpose),permit->{
            var reply=client.complete(request,budgets.dispatchTimeout(claim,permit));
            budgets.recordUsage(permit,reply);
            return reply;
        });
    }
}
