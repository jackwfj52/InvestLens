package org.jack.backend.report;

import org.jack.backend.common.exception.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.math.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ReportNumbers {
    public record Figure(String metricId,String name,String value,String unit,String currency,String displayValue,String formulaVersion,List<String> evidenceRefs) { }
    private static final Set<String> TEXT_FIELDS=Set.of("text","conditions","invalidationConditions","unknowns","thesisAssessment","nextChecks","reason","message","limitations");
    private static final Pattern NUMBER=Pattern.compile("[\\p{N}]|百分|千分|[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]{2,}|[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+(?:点|成|倍|元|美元|人民币|个百分点)|(?i)(?<![a-z])(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|million|billion)(?![a-z])");
    public List<Figure> validate(JsonNode report) {
        scan(report,false);
        var figures=new ArrayList<Figure>();var ids=new HashSet<String>();
        for(JsonNode metric:report.path("metricBundle").path("metrics")) {
            String name=metric.path("name").asString(),unit=metric.path("unit").asString();
            Set<String> allowed=switch(name) {
                case "PERIOD_RETURN","ANNUALIZED_VOLATILITY","MAX_DRAWDOWN","TARGET_WEIGHT_AFTER","SCENARIO_RETURN_BEFORE","SCENARIO_RETURN_AFTER" -> Set.of("RATIO");
                case "WEIGHT_CHANGE","SCENARIO_RETURN_CHANGE" -> Set.of("PERCENTAGE_POINT");
                case "SCENARIO_PNL_BEFORE","SCENARIO_PNL_AFTER" -> Set.of(metric.path("parameters").path("valuationBasis").asString().equals("ABSOLUTE")?"CURRENCY":"BASE_CURRENCY_UNIT");
                default -> throw invalid();
            };
            if(!allowed.contains(unit) || !ids.add(metric.path("metricId").asString()))throw invalid();
            if(!metric.path("status").asString().equals("CALCULABLE"))continue;
            String currency=metric.path("currency").isNull()?null:metric.path("currency").asString();
            boolean amount=unit.equals("CURRENCY") || unit.equals("BASE_CURRENCY_UNIT");
            if(amount && (currency==null || !currency.matches("[A-Z]{3}")))throw invalid();
            if(!amount && currency!=null)throw invalid();
            BigDecimal value;
            try {value=new BigDecimal(metric.path("value").asString());}catch(RuntimeException failure){throw invalid();}
            if(value.precision()>100 || Math.abs(value.scale())>30)throw invalid();
            BigDecimal rendered=unit.equals("RATIO")?value.movePointRight(2):value;
            String suffix=switch(unit){case "RATIO"->"%";case "PERCENTAGE_POINT"->" 个百分点";case "CURRENCY"->" "+currency;default->" 基准单位";};
            var refs=new ArrayList<String>();metric.path("evidenceRefs").forEach(ref->refs.add(ref.asString()));
            figures.add(new Figure(metric.path("metricId").asString(),name,metric.path("value").asString(),unit,currency,
                    rendered.setScale(2,RoundingMode.HALF_UP).toPlainString()+suffix,metric.path("formulaVersion").asString(),List.copyOf(refs)));
        }
        return List.copyOf(figures);
    }
    private void scan(JsonNode node,boolean freeText) {
        if(node.isString() && freeText && NUMBER.matcher(Normalizer.normalize(node.asString(),Normalizer.Form.NFKC)).find())throw invalid();
        if(node.isArray())node.forEach(value->scan(value,freeText));
        if(node.isObject())node.properties().forEach(entry->scan(entry.getValue(),TEXT_FIELDS.contains(entry.getKey())));
    }
    private static BusinessException invalid(){return new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);}
}
