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
        return result;
    }
}
