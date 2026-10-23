package org.jack.backend.agent.planner;

import org.jack.backend.common.exception.*;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.portfolio.*;
import org.jack.backend.llm.*;
import org.jack.backend.workflow.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.jack.backend.workflow.CallBudgetData.Purpose;

@Service
public class PlannerService {
    private final BudgetedLlm llm;
    private final PlanParser parser;
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    private final PortfolioInstrumentCatalog catalog;
    private final ObjectMapper json;
    public PlannerService(BudgetedLlm llm,PlanParser parser,DecisionService decisions,PortfolioService portfolios,PortfolioInstrumentCatalog catalog,ObjectMapper json){
        this.llm=llm;this.parser=parser;this.decisions=decisions;this.portfolios=portfolios;this.catalog=catalog;this.json=json;
    }
    public Map<String,Object> plan(WorkflowClaimService.Claim claim) {
        var run=claim.run();var decision=decisions.requireConfirmed(run.decisionId(),run.decisionVersion());
        var portfolio=portfolios.getSnapshot(run.portfolioSnapshotId());
        Set<String> instruments=portfolio.positions().stream().map(position->position.instrumentId())
                .filter(id->catalog.require(id,run.dataMode(),"instrumentId").kind()!=PortfolioInstrumentCatalog.Kind.CASH).collect(Collectors.toSet());
        var context=new PlanParser.Context(run.runId(),decision.decisionId(),decision.version(),decision.hypotheses().stream().map(h->h.hypothesisId()).collect(Collectors.toSet()),instruments);
        String prompt;
        try{prompt=new ClassPathResource("prompts/planner-v1.txt").getContentAsString(StandardCharsets.UTF_8)
                +"\nPlan JSON Schema:\n"+new ClassPathResource("prompts/plan-v1.schema.json").getContentAsString(StandardCharsets.UTF_8);}
        catch(java.io.IOException missing){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
        String input=json.writeValueAsString(Map.of("fixed",context,"hypotheses",decision.hypotheses(),"action",decision.action(),"horizon",decision.horizon(),"constraints",decision.constraints(),
                "windowPolicy","2026 only, past or current New York date, max 120 calendar days; no invented shocks"));
        for(int attempt=0;attempt<2;attempt++) {
            try {
                var reply=llm.complete(claim,attempt==0?Purpose.STANDARD:Purpose.SCHEMA_REPAIR,new LlmClient.Request(prompt+(attempt==0?"":"\nRepair invalid JSON/schema using the same fixed inputs."),input,2048));
                return json.convertValue(parser.parse(reply.content(),context),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});
            }catch(BusinessException invalid){if(invalid.getErrorCode()!=ErrorCode.MODEL_OUTPUT_INVALID || attempt==1)throw invalid;}
        }
        throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
    }
}
