package org.jack.backend.agent.planner;

import org.jack.backend.common.exception.*;
import org.jack.backend.market.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Component
public class PlanParser {
    public static final String VERSION="planner-v1";
    public static final Set<String> TOOLS=Set.of("Market.getHistory","Portfolio.getSnapshot","Quant.calculate","Quant.runScenario");
    public record Context(String runId,String decisionId,int decisionVersion,Set<String> hypothesisIds,Set<String> instrumentIds) { }
    private final Clock clock;
    private final ObjectMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public PlanParser(Clock clock){this.clock=clock;}
    public JsonNode parse(String content,Context context) {
        try {
            if(content==null || content.length()>65536)throw new IllegalArgumentException();
            JsonNode plan=json.readTree(content);
            fields(plan,"schemaVersion","planId","runId","decisionRef","hypothesisIds","dataNeeds","steps","promptVersion");
            equal(plan,"schemaVersion","1.0.0");equal(plan,"promptVersion",VERSION);equal(plan,"runId",context.runId());id(plan,"planId");
            var ref=plan.get("decisionRef");fields(ref,"decisionId","version");equal(ref,"decisionId",context.decisionId());
            if(!ref.get("version").isIntegralNumber() || !ref.get("version").canConvertToInt() || ref.get("version").asInt()!=context.decisionVersion())throw new IllegalArgumentException();
            if(!strings(plan.get("hypothesisIds"),100).equals(context.hypothesisIds()))throw new IllegalArgumentException();
            var needs=plan.get("dataNeeds");array(needs,0,3);var assets=new HashSet<String>();
            for(JsonNode need:needs) {
                fields(need,"instrumentId","purpose","window");String asset=id(need,"instrumentId");
                if(!context.instrumentIds().contains(asset) || !assets.add(asset))throw new IllegalArgumentException();
                text(need,"purpose",1000);var window=need.get("window");fields(window,"start","end","interval");equal(window,"interval","1d");
                MarketWindow.validate(new MarketData.Query(asset,LocalDate.parse(text(window,"start",10)),LocalDate.parse(text(window,"end",10))),clock);
            }
            var steps=plan.get("steps");array(steps,1,6);var dependencies=new HashMap<String,Set<String>>();
            boolean history=false;
            for(JsonNode step:steps) {
                fields(step,"stepKey","tool","dependsOn");String key=id(step,"stepKey"),tool=text(step,"tool",64);
                if(!TOOLS.contains(tool) || dependencies.putIfAbsent(key,strings(step.get("dependsOn"),6))!=null)throw new IllegalArgumentException();
                history|=tool.equals("Market.getHistory");
            }
            if(history!=!assets.isEmpty())throw new IllegalArgumentException();
            var visited=new HashSet<String>();for(String key:dependencies.keySet())visit(key,dependencies,new HashSet<>(),visited);
            return plan;
        }catch(RuntimeException invalid){throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
    }
    private static void visit(String key,Map<String,Set<String>> graph,Set<String> path,Set<String> visited) {
        if(visited.contains(key))return;
        if(!graph.containsKey(key) || !path.add(key))throw new IllegalArgumentException();
        for(String dependency:graph.get(key))visit(dependency,graph,path,visited);
        path.remove(key);visited.add(key);
    }
    private static void fields(JsonNode node,String... names) {
        if(node==null || !node.isObject() || node.size()!=names.length)throw new IllegalArgumentException();
        for(String name:names)if(!node.has(name) || node.get(name).isNull())throw new IllegalArgumentException();
    }
    private static String text(JsonNode node,String name,int max) {
        JsonNode value=node.get(name);
        if(value==null || !value.isString() || value.asString().isBlank() || value.asString().length()>max
                || value.asString().matches("(?is).*(?:https?|file|ftp)://.*"))throw new IllegalArgumentException();
        return value.asString();
    }
    private static String id(JsonNode node,String name) {
        String value=text(node,name,160);if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))throw new IllegalArgumentException();return value;
    }
    private static void equal(JsonNode node,String name,String expected){if(!text(node,name,160).equals(expected))throw new IllegalArgumentException();}
    private static void array(JsonNode node,int min,int max){if(node==null || !node.isArray() || node.size()<min || node.size()>max)throw new IllegalArgumentException();}
    private static Set<String> strings(JsonNode node,int max) {
        array(node,0,max);var values=new HashSet<String>();
        for(JsonNode value:node)if(!value.isString() || !value.asString().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}") || !values.add(value.asString()))throw new IllegalArgumentException();
        return values;
    }
}
