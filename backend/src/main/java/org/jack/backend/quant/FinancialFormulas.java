package org.jack.backend.quant;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Pure, deterministic price math. No IO, Spring, model, portfolio cash flows or calendar inference. */
public final class FinancialFormulas {
    public static final String RETURN_VERSION = "simple-return-v1";
    public static final String VOLATILITY_VERSION = "sample-volatility-v1";
    public static final String DRAWDOWN_VERSION = "peak-drawdown-v1";
    public static final int DEFAULT_ANNUALIZATION_FACTOR = 252;
    private static final MathContext MC = MathContext.DECIMAL128;
    private FinancialFormulas() { }

    public enum Reason { INSUFFICIENT_SAMPLES, MISSING_PRICE, INVALID_PRICE, INVALID_ANNUALIZATION_FACTOR }
    public record Value(String value, Reason reason) {
        public boolean calculable() { return reason == null; }
    }
    public record Returns(List<String> values, Reason reason) {
        public Returns { values = List.copyOf(values); }
        public boolean calculable() { return reason == null; }
    }

    /** P_to / P_from - 1. Inputs must be strictly positive; a missing value never becomes zero. */
    public static Value simpleReturn(BigDecimal from, BigDecimal to) {
        if (from == null || to == null) return unavailable(Reason.MISSING_PRICE);
        if (from.signum() <= 0 || to.signum() <= 0) return unavailable(Reason.INVALID_PRICE);
        return available(returnValue(from, to));
    }

    public static Returns simpleReturns(List<BigDecimal> prices) {
        Reason reason = validate(prices, 2);
        if (reason != null) return new Returns(List.of(), reason);
        return new Returns(rawReturns(prices).stream().map(FinancialFormulas::decimal).toList(), null);
    }

    /** Validate the full series, including interior missing values, before taking the endpoints. */
    public static Value cumulativeReturn(List<BigDecimal> prices) {
        Reason reason = validate(prices, 2);
        if (reason != null) return unavailable(reason);
        return available(returnValue(prices.get(0), prices.get(prices.size() - 1)));
    }

    /** Sample SD of simple returns (n-1), times sqrt(A). Business minimum 30 is applied in G13. */
    public static Value annualizedVolatility(List<BigDecimal> prices, int annualizationFactor) {
        if (annualizationFactor <= 0) return unavailable(Reason.INVALID_ANNUALIZATION_FACTOR);
        Reason reason = validate(prices, 3);
        if (reason != null) return unavailable(reason);
        List<BigDecimal> returns = rawReturns(prices);
        BigDecimal n = BigDecimal.valueOf(returns.size());
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(n, MC);
        BigDecimal squaredDeviations = BigDecimal.ZERO;
        for (BigDecimal value : returns) {
            BigDecimal deviation = value.subtract(mean);
            squaredDeviations = squaredDeviations.add(deviation.multiply(deviation));
        }
        BigDecimal variance = squaredDeviations.divide(n.subtract(BigDecimal.ONE), MC);
        return available(variance.sqrt(MC).multiply(BigDecimal.valueOf(annualizationFactor).sqrt(MC), MC));
    }

    public static Value annualizedVolatility(List<BigDecimal> prices) {
        return annualizedVolatility(prices, DEFAULT_ANNUALIZATION_FACTOR);
    }

    /** Nonnegative loss fraction from the running peak; later recovery does not erase an earlier loss. */
    public static Value maxDrawdown(List<BigDecimal> prices) {
        Reason reason = validate(prices, 2);
        if (reason != null) return unavailable(reason);
        BigDecimal peak = prices.get(0), drawdown = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            peak = peak.max(price);
            drawdown = drawdown.max(peak.subtract(price).divide(peak, MC));
        }
        return available(drawdown);
    }

    private static List<BigDecimal> rawReturns(List<BigDecimal> prices) {
        var values = new ArrayList<BigDecimal>(prices.size() - 1);
        for (int i = 1; i < prices.size(); i++) values.add(returnValue(prices.get(i - 1), prices.get(i)));
        return values;
    }
    private static BigDecimal returnValue(BigDecimal from, BigDecimal to) {
        return to.subtract(from).divide(from, MC);
    }
    private static Reason validate(List<BigDecimal> prices, int minimumPrices) {
        if (prices == null) return Reason.MISSING_PRICE;
        for (BigDecimal price : prices) {
            if (price == null) return Reason.MISSING_PRICE;
            if (price.signum() <= 0) return Reason.INVALID_PRICE;
        }
        return prices.size() < minimumPrices ? Reason.INSUFFICIENT_SAMPLES : null;
    }
    private static Value unavailable(Reason reason) { return new Value(null, reason); }
    private static Value available(BigDecimal value) { return new Value(decimal(value), null); }
    private static String decimal(BigDecimal value) {
        return value.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
