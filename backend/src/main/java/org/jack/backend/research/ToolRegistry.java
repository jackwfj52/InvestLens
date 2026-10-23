package org.jack.backend.research;

import org.jack.backend.agent.planner.PlanParser;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.evidence.EvidenceData.Gap;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import static org.jack.backend.workflow.CallBudgetData.*;

@Service
public class ToolRegistry {
    private final PlanParser parser;
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    private final PortfolioInstrumentCatalog instruments;
    private final MarketInstrumentCatalog market;
    private final MarketQualityInspection inspection;
    private final BudgetedCalls calls;
    private final CallBudgetService budgets;
    private final ResearchToolStore store;
    private final ObjectMapper json;
    public ToolRegistry(PlanParser parser,DecisionService decisions,PortfolioService portfolios,PortfolioInstrumentCatalog instruments,MarketInstrumentCatalog market,
            MarketQualityInspection inspection,BudgetedCalls calls,CallBudgetService budgets,ResearchToolStore store,ObjectMapper json){
        this.parser=parser;this.decisions=decisions;this.portfolios=portfolios;this.instruments=instruments;this.market=market;
        this.inspection=inspection;this.calls=calls;this.budgets=budgets;this.store=store;this.json=json;
    }
    public ResearchData.Tools execute(WorkflowClaimService.Claim claim,JsonNode supplied,String scenarioId) {
        if(claim.step().stepKey()!=WorkflowData.StepKey.RESEARCH)throw new BusinessException(ErrorCode.INVALID_STATE);
        var run=claim.run();var decision=decisions.requireConfirmed(run.decisionId(),run.decisionVersion());var portfolio=portfolios.getSnapshot(run.portfolioSnapshotId());
        Set<String> assets=portfolio.positions().stream().map(position->position.instrumentId()).filter(id->instruments.require(id,run.dataMode(),"instrumentId").kind()!=PortfolioInstrumentCatalog.Kind.CASH).collect(Collectors.toSet());
        JsonNode plan=parser.parse(json.writeValueAsString(supplied),new PlanParser.Context(run.runId(),run.decisionId(),run.decisionVersion(),decision.hypotheses().stream().map(h->h.hypothesisId()).collect(Collectors.toSet()),assets));
        String hash=CanonicalJson.sha256(plan);budgets.requireActive(claim);var existing=store.existing(run.runId(),hash);if(existing!=null)return existing;
        var queries=new ArrayList<MarketData.Query>();var collected=new LinkedHashMap<MarketData.Query,MarketQuality.Result>();var gaps=new ArrayList<Gap>();
        boolean calculate=false,scenario=false;
        for(JsonNode step:plan.get("steps")) {calculate|=step.get("tool").asString().equals("Quant.calculate");scenario|=step.get("tool").asString().equals("Quant.runScenario");}
        if(!scenario && scenarioId!=null)throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        if(scenarioId!=null && !plan.get("dataNeeds").isEmpty())gaps.add(new Gap("FIXED_SCENARIO_INPUTS",scenarioId,"固定情景已有输入，不自动刷新模型请求的行情窗口"));
        for(JsonNode need:plan.get("dataNeeds")) {
            var query=new MarketData.Query(need.get("instrumentId").asString(),LocalDate.parse(need.get("window").get("start").asString()),LocalDate.parse(need.get("window").get("end").asString()));
            if(scenarioId!=null)continue;
            if(run.dataMode()!=PortfolioDtos.DataMode.LIVE){gaps.add(new Gap("FIXED_MARKET_INPUT_REQUIRED",query.instrumentId(),"回放和合成模式禁止实时取数"));continue;}
            try {
                market.requireResearchEligible(query.instrumentId());
                var result=calls.executeWithRetry(claim,new Request(Kind.DATA,Purpose.STANDARD,query.instrumentId(),query.start(),query.end()),
                        permit->inspection.inspect(query,budgets.dispatchTimeout(claim,permit)));
                queries.add(query);collected.put(query,result);
            }catch(BusinessException failure){
                if(failure.getErrorCode()==ErrorCode.VERSION_CONFLICT || failure.getErrorCode()==ErrorCode.BUDGET_EXHAUSTED)throw failure;
                gaps.add(new Gap(failure.getErrorCode().name(),query.instrumentId(),"工具数据不可用，保留明确缺口"));
            }
        }
        return store.save(claim,hash,queries,collected,scenarioId,calculate,gaps);
    }
}
