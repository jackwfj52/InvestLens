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
    private final org.jack.backend.agent.planner.PlannerService planner;
    private final boolean modelEnabled;
    private final org.jack.backend.research.ResearchMapper research;
    private final org.jack.backend.common.identity.CurrentOwner owner;
    private final org.jack.backend.research.ToolRegistry tools;
    private final org.jack.backend.research.ResearchFreezeService freeze;
    private final WorkflowStore store;
    private final WorkflowMapper mapper;
    private final tools.jackson.databind.ObjectMapper json;
    public PreparationHandler(DecisionService decisions,PortfolioService portfolios,org.jack.backend.agent.planner.PlannerService planner,
            boolean modelEnabled){this(decisions,portfolios,planner,modelEnabled,null,null,null,null,null,null,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public PreparationHandler(DecisionService decisions,PortfolioService portfolios,org.jack.backend.agent.planner.PlannerService planner,
            @org.springframework.beans.factory.annotation.Value("${INVESTLENS_LLM_ENABLED:false}") boolean modelEnabled,
            org.jack.backend.research.ResearchMapper research,org.jack.backend.common.identity.CurrentOwner owner,org.jack.backend.research.ToolRegistry tools,
            org.jack.backend.research.ResearchFreezeService freeze,WorkflowStore store,WorkflowMapper mapper,tools.jackson.databind.ObjectMapper json){
        this.decisions=decisions;this.portfolios=portfolios;this.planner=planner;this.modelEnabled=modelEnabled;
        this.research=research;this.owner=owner;this.tools=tools;this.freeze=freeze;this.store=store;this.mapper=mapper;this.json=json;
    }
    @Override public Result execute(WorkflowClaimService.Claim claim) {
        var r=claim.run();var d=decisions.requireConfirmed(r.decisionId(),r.decisionVersion());var p=portfolios.getSnapshot(r.portfolioSnapshotId());
        String scenarioId=research==null?null:research.scenario(r.runId(),owner.id());
        if(claim.step().stepKey()==StepKey.PLAN && modelEnabled)return new Result(planner.plan(claim),StepKey.RESEARCH,null);
        if(claim.step().stepKey()==StepKey.PLAN && scenarioId!=null)return new Result(Map.of("schemaVersion","1.0.0","planId","plan_"+r.runId(),"runId",r.runId(),
                "decisionRef",Map.of("decisionId",d.decisionId(),"version",d.version()),"hypothesisIds",d.hypotheses().stream().map(h->h.hypothesisId()).toList(),"dataNeeds",List.of(),
                "steps",List.of(Map.of("stepKey","portfolio","tool","Portfolio.getSnapshot","dependsOn",List.of()),Map.of("stepKey","scenario","tool","Quant.runScenario","dependsOn",List.of("portfolio")),
                        Map.of("stepKey","history","tool","Quant.calculate","dependsOn",List.of("portfolio"))),"promptVersion","planner-v1"),StepKey.RESEARCH,null);
        if((modelEnabled || scenarioId!=null) && claim.step().stepKey()==StepKey.RESEARCH) {
            var plan=json.readTree(mapper.output(store.step(r.runId(),StepKey.PLAN).outputRef()));
            var result=tools.execute(claim,plan,scenarioId);return new Result(json.convertValue(json.valueToTree(result),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}),StepKey.QUANT,null);
        }
        if((modelEnabled || scenarioId!=null) && claim.step().stepKey()==StepKey.QUANT) {
            var plan=json.readTree(mapper.output(store.step(r.runId(),StepKey.PLAN).outputRef()));var snapshot=freeze.freeze(claim,plan).snapshot();
            return new Result(Map.of("kind","FROZEN_RESEARCH","snapshotId",snapshot.get("snapshotId").asString(),"snapshotHash",snapshot.get("snapshotHash").asString()),StepKey.BULL,null);
        }
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
