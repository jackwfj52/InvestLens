package org.jack.backend.workflow;

import org.jack.backend.decision.DecisionService;
import org.jack.backend.portfolio.PortfolioService;
import org.springframework.stereotype.Component;
import java.util.*;
import java.math.BigDecimal;
import static org.jack.backend.quant.MetricFactory.decimal;
import static org.jack.backend.workflow.WorkflowData.*;

/** Deterministic preparation only. Market windows, shocks and model claims are never invented. */
@Component
public class PreparationHandler implements WorkflowHandler {
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    public PreparationHandler(DecisionService decisions,PortfolioService portfolios){this.decisions=decisions;this.portfolios=portfolios;}
    @Override public Result execute(WorkflowClaimService.Claim claim) {
        var r=claim.run();var d=decisions.requireConfirmed(r.decisionId(),r.decisionVersion());var p=portfolios.getSnapshot(r.portfolioSnapshotId());
        if(claim.step().stepKey()==StepKey.PLAN) return new Result(Map.of("schemaVersion","1.0.0","kind","FIXED_INPUT_PLAN",
                "decisionId",d.decisionId(),"decisionVersion",d.version(),"portfolioSnapshotId",p.snapshotId(),"portfolioHash",p.contentHash(),
                "dataMode",p.dataMode().name(),"modelSteps","DISABLED","gaps",List.of("MODEL_HANDLERS_NOT_IMPLEMENTED")),StepKey.RESEARCH,null);
        if(claim.step().stepKey()==StepKey.RESEARCH) return new Result(Map.of("schemaVersion","1.0.0","kind","LOCAL_INPUT_RESEARCH",
                "portfolioSnapshotId",p.snapshotId(),"portfolioHash",p.contentHash(),"dataMode",p.dataMode().name(),"positions",p.positions(),
                "marketInputs",List.of(),"gaps",List.of("NO_MARKET_WINDOW_SELECTED","MODEL_HANDLERS_NOT_IMPLEMENTED")),StepKey.QUANT,null);
        if(claim.step().stepKey()==StepKey.QUANT) {
            var weights=p.positions().stream().map(position->new BigDecimal(position.weight())).toList();
            BigDecimal hhi=weights.stream().map(w->w.multiply(w)).reduce(BigDecimal.ZERO,BigDecimal::add);
            return new Result(Map.of("schemaVersion","1.0.0","kind","HOLDING_CONCENTRATION","portfolioHash",p.contentHash(),
                    "dataMode",p.dataMode().name(),"formulaVersion","weight-concentration-v1","unit","RATIO","includesCash",true,
                    "hhi",decimal(hhi),"maxWeight",decimal(weights.stream().max(BigDecimal::compareTo).orElseThrow()),
                    "gaps",List.of("HISTORICAL_METRICS_NOT_CALCULABLE","SCENARIO_REQUIRES_EXPLICIT_SHOCKS")),StepKey.BULL,null);
        }
        return new Result(Map.of("kind","MISSING_HANDLER","step",claim.step().stepKey().name()),null,"PROVIDER_NOT_CONFIGURED");
    }
}
