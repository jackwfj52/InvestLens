package org.jack.backend.agent.planner;

import org.jack.backend.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlanParserTests {
    final PlanParser parser=new PlanParser(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC));
    final PlanParser.Context context=new PlanParser.Context("run_test","decision_test",1,Set.of("hyp_test"),Set.of("GLD","SLV","AAPL","FOURTH"));
    final String valid="""
        {"schemaVersion":"1.0.0","planId":"plan_test","runId":"run_test","decisionRef":{"decisionId":"decision_test","version":1},
        "hypothesisIds":["hyp_test"],"dataNeeds":[{"instrumentId":"GLD","purpose":"history","window":{"start":"2026-06-01","end":"2026-06-05","interval":"1d"}}],
        "steps":[{"stepKey":"history","tool":"Market.getHistory","dependsOn":[]},{"stepKey":"quant","tool":"Quant.calculate","dependsOn":["history"]}],"promptVersion":"planner-v1"}
        """;
    @Test void acceptsBoundedPlan(){assertThat(parser.parse(valid,context).get("steps").size()).isEqualTo(2);}
    @Test void rejectsMalformedUnknownToolsUrlsAndArbitraryParameters() {
        for(String invalid:List.of("```json\n"+valid+"```",valid+" {}",valid.replace("\"planId\":","\"planId\":\"duplicate\",\"planId\":"),
                valid.replace("Market.getHistory","HTTP.get"),valid.replace("\"tool\":\"Market.getHistory\"","\"tool\":\"Market.getHistory\",\"url\":\"https://example.com\""),
                valid.replace("\"purpose\":\"history\"","\"purpose\":\"history\",\"ownerId\":\"other\""),valid.replace("\"GLD\"","\"UNLISTED\""))) {
            assertThatThrownBy(()->parser.parse(invalid,context)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void rejectsCyclesMissingDependenciesIdentityChangesAndWindowViolations() {
        for(String invalid:List.of(valid.replace("\"dependsOn\":[]","\"dependsOn\":[\"quant\"]"),valid.replace("[\"history\"]","[\"unknown\"]"),
                valid.replace("run_test","run_other"),valid.replace("hyp_test","hyp_other"),valid.replace("2026-06-05","2027-06-05"),valid.replace("2026-06-01","2026-01-01"),
                valid.replace("\"version\":1","\"version\":1.0")))assertThatThrownBy(()->parser.parse(invalid,context)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsMoreThanThreeAssetsOrMultipleWindows() {
        String need="{\"instrumentId\":\"%s\",\"purpose\":\"history\",\"window\":{\"start\":\"2026-06-01\",\"end\":\"2026-06-05\",\"interval\":\"1d\"}}";
        String original=need.formatted("GLD");
        assertThatThrownBy(()->parser.parse(valid.replace(original,original+","+original),context)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->parser.parse(valid.replace(original,String.join(",",List.of(original,need.formatted("SLV"),need.formatted("AAPL"),need.formatted("FOURTH")))),context)).isInstanceOf(BusinessException.class);
    }
}
