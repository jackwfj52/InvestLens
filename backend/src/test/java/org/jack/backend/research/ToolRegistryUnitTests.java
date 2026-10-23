package org.jack.backend.research;

import org.jack.backend.agent.planner.PlanParser;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.decision.*;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.*;
import org.jack.backend.workflow.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolRegistryUnitTests {
    @Test void duplicateMarketStepsFetchOnlyOnceAndUnsafeDependenciesAreRejected() {
        var json=JsonMapper.builder().build();var decisions=mock(DecisionService.class);var portfolios=mock(PortfolioService.class);var instruments=mock(PortfolioInstrumentCatalog.class);
        var catalog=mock(MarketInstrumentCatalog.class);var inspection=mock(MarketQualityInspection.class);var calls=mock(BudgetedCalls.class);var budgets=mock(CallBudgetService.class);var store=mock(ResearchToolStore.class);
        var run=mock(WorkflowData.Run.class);when(run.runId()).thenReturn("run_test");when(run.decisionId()).thenReturn("decision_test");when(run.decisionVersion()).thenReturn(1);when(run.portfolioSnapshotId()).thenReturn("portfolio_test");when(run.dataMode()).thenReturn(PortfolioDtos.DataMode.LIVE);
        var step=mock(WorkflowData.Step.class);when(step.stepKey()).thenReturn(WorkflowData.StepKey.RESEARCH);var claim=new WorkflowClaimService.Claim(run,step,null);
        var decision=mock(DecisionDtos.Version.class);when(decisions.requireConfirmed("decision_test",1)).thenReturn(decision);when(decision.hypotheses()).thenReturn(List.of(new DecisionDtos.Hypothesis("1.0.0","hyp_test","test","test","test","UNVERIFIED")));
        var portfolio=mock(PortfolioDtos.Snapshot.class);when(portfolios.getSnapshot("portfolio_test")).thenReturn(portfolio);when(portfolio.positions()).thenReturn(List.of(new PortfolioDtos.Position("asset_test","USD","1")));
        when(instruments.require(any(),any(),any())).thenReturn(new PortfolioInstrumentCatalog.Identity(PortfolioInstrumentCatalog.Kind.ETF,"USD",false));
        when(budgets.dispatchTimeout(any(),any())).thenReturn(1000L);var result=mock(MarketQuality.Result.class);when(inspection.inspect(any(),eq(1000L))).thenReturn(result);
        when(calls.executeWithRetry(any(),any(),any())).thenAnswer(invocation->{Function<CallBudgetData.Permit,MarketQuality.Result> operation=invocation.getArgument(2);return operation.apply(mock(CallBudgetData.Permit.class));});
        var registry=new ToolRegistry(new PlanParser(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC)),decisions,portfolios,instruments,catalog,inspection,calls,budgets,store,json);
        var plan=json.valueToTree(Map.of("schemaVersion","1.0.0","planId","plan_test","runId","run_test","decisionRef",Map.of("decisionId","decision_test","version",1),"hypothesisIds",List.of("hyp_test"),
                "dataNeeds",List.of(Map.of("instrumentId","asset_test","purpose","history","window",Map.of("start","2026-06-01","end","2026-06-05","interval","1d"))),
                "steps",List.of(Map.of("stepKey","first","tool","Market.getHistory","dependsOn",List.of()),Map.of("stepKey","second","tool","Market.getHistory","dependsOn",List.of()),Map.of("stepKey","quant","tool","Quant.calculate","dependsOn",List.of("first","second"))),"promptVersion","planner-v1"));
        registry.execute(claim,plan,null);verify(inspection,times(1)).inspect(any(),eq(1000L));verify(calls,times(1)).executeWithRetry(any(),any(),any());
        ((tools.jackson.databind.node.ObjectNode)plan.get("steps").get(2)).set("dependsOn",json.createArrayNode());
        assertThatThrownBy(()->registry.execute(claim,plan,null)).isInstanceOf(BusinessException.class);verifyNoMoreInteractions(inspection);
    }
}
