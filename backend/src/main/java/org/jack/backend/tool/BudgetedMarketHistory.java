package org.jack.backend.tool;

import org.jack.backend.market.*;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import static org.jack.backend.workflow.CallBudgetData.*;

@Service
public class BudgetedMarketHistory {
    private final BudgetedCalls calls;
    private final CallBudgetService budgets;
    private final MarketHistoryTool tool;
    public BudgetedMarketHistory(BudgetedCalls calls,CallBudgetService budgets,MarketHistoryTool tool){this.calls=calls;this.budgets=budgets;this.tool=tool;}
    public MarketQuality.Result execute(WorkflowClaimService.Claim claim,MarketData.Query query) {
        return calls.executeWithRetry(claim,new Request(Kind.DATA,Purpose.STANDARD,query.instrumentId(),query.start(),query.end()),
                permit->tool.execute(query,budgets.dispatchTimeout(claim,permit)));
    }
}
