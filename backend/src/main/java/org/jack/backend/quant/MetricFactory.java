package org.jack.backend.quant;

import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.common.validation.ContractPatterns;
import org.jack.backend.market.MarketQuality.Quality;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static org.jack.backend.quant.MetricData.*;

@Component
public class MetricFactory {
    private final ObjectMapper json;
    public MetricFactory(ObjectMapper json) { this.json = json; }
    public Metric create(String name, String value, String unit, String currency, String inputHash,
                         String formulaVersion, Parameters parameters, Window window, int count,
                         Quality quality, String reason, List<String> refs) {
        if (value != null && !value.matches(ContractPatterns.DECIMAL)) { value = null; reason = "NUMERIC_RANGE"; quality = Quality.INVALID; }
        return new Metric("1.0.0", "metric_" + UUID.randomUUID(), name,
                value == null ? Status.NOT_CALCULABLE : Status.CALCULABLE, value, unit, currency, inputHash,
                formulaVersion, parameters, window, count, value == null ? quality : Quality.VALID, reason, refs);
    }
    public Map<String, String> hashes(List<Metric> metrics) {
        var hashes = new TreeMap<String, String>();
        metrics.forEach(m -> hashes.put(m.metricId(), CanonicalJson.sha256(json.valueToTree(m))));
        return hashes;
    }
    public static String decimal(BigDecimal value) {
        return value.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
