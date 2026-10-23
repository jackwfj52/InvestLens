package org.jack.backend.research;

import org.jack.backend.report.ReportNumbers;
import org.jack.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReportNumbersTests {
    private final JsonMapper json=JsonMapper.builder().build();
    private final ReportNumbers numbers=new ReportNumbers();
    @Test void rendersOnlyStructuredBackendValues() {
        var report=json.readTree("""
                {"metricBundle":{"metrics":[{"metricId":"m","name":"SCENARIO_RETURN_AFTER","unit":"RATIO","currency":null,"status":"CALCULABLE","value":"-0.085","formulaVersion":"v1","evidenceRefs":["e"]}]}}
                """);
        assertThat(numbers.validate(report).get(0).displayValue()).isEqualTo("-8.50%");
    }
    @Test void rejectsFabricatedAndDisguisedFreeTextNumbers() {
        for(String text:new String[]{"预期收益87%","收益８７％","收益百分之八十七","上涨三成","收益five percent","盈利壹佰元"}) {
            var report=json.createObjectNode();report.putObject("verdict").putArray("unknowns").add(text);
            assertThatThrownBy(()->numbers.validate(report)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void rejectsUnitMismatch() {
        var report=json.readTree("""
                {"metricBundle":{"metrics":[{"metricId":"m","name":"SCENARIO_RETURN_AFTER","unit":"CURRENCY","currency":"USD","status":"CALCULABLE","value":"9"}]}}
                """);
        assertThatThrownBy(()->numbers.validate(report)).isInstanceOf(BusinessException.class);
    }
}
