package org.jack.backend.quant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.quant.FinancialFormulas.*;

class FinancialFormulasTests {
    List<BigDecimal> prices(String... values) {
        return Arrays.stream(values).map(v -> v == null ? null : new BigDecimal(v)).toList();
    }

    @Test void hundredToHundredTenToNinetyNineMatchesContract() {
        var sample = prices("100", "110", "99");
        assertThat(simpleReturns(sample).values()).containsExactly("0.1", "-0.1");
        assertThat(cumulativeReturn(sample).value()).isEqualTo("-0.01");
        assertThat(maxDrawdown(sample).value()).isEqualTo("0.1");
        assertThat(annualizedVolatility(sample, 1).value()).isEqualTo("0.1414213562");
        assertThat(annualizedVolatility(sample).value()).isEqualTo("2.2449944321");
    }

    @Test void sampleDenominatorAndAnnualizationAreIndependent() {
        // Returns 0, .1, -.1; mean 0, sample variance .01 (population variance would be .00666...).
        var sample = prices("100", "100", "110", "99");
        assertThat(annualizedVolatility(sample, 1).value()).isEqualTo("0.1");
        assertThat(annualizedVolatility(sample, 4).value()).isEqualTo("0.2");
        assertThat(annualizedVolatility(sample, 252).value()).isEqualTo("1.5874507866");
    }

    @Test void nonzeroMeanIsSubtractedBeforeVariance() {
        // .1, .2, 0 => mean .1, sample SD .1.
        assertThat(annualizedVolatility(prices("100", "110", "132", "132"), 1).value()).isEqualTo("0.1");
    }

    @Test void cumulativeReturnCompoundsRatherThanAddingPeriodReturns() {
        assertThat(cumulativeReturn(prices("100", "110", "99")).value()).isEqualTo("-0.01");
        assertThat(cumulativeReturn(prices("100", "110", "121")).value()).isEqualTo("0.21");
    }

    @Test void runningPeakTracksNewHighsAndKeepsDrawdownAfterRecovery() {
        assertThat(maxDrawdown(prices("100", "80", "120", "90", "130")).value()).isEqualTo("0.25");
        assertThat(maxDrawdown(prices("100", "90", "81")).value()).isEqualTo("0.19");
        assertThat(maxDrawdown(prices("100", "110", "120")).value()).isEqualTo("0");
    }

    @Test void constantPricesProduceTrueZero() {
        var sample = prices("100", "100", "100");
        assertThat(cumulativeReturn(sample)).isEqualTo(new Value("0", null));
        assertThat(maxDrawdown(sample)).isEqualTo(new Value("0", null));
        assertThat(annualizedVolatility(sample)).isEqualTo(new Value("0", null));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "-0.1"})
    void zeroAndNegativePricesAreNeverSilentlySkipped(String invalid) {
        var sample = prices("100", invalid, "110");
        for (Value result : List.of(cumulativeReturn(sample), maxDrawdown(sample), annualizedVolatility(sample),
                simpleReturn(new BigDecimal(invalid), BigDecimal.TEN), simpleReturn(BigDecimal.TEN, new BigDecimal(invalid)))) {
            assertThat(result.value()).isNull(); assertThat(result.reason()).isEqualTo(Reason.INVALID_PRICE);
        }
        assertThat(simpleReturns(sample).values()).isEmpty();
        assertThat(simpleReturns(sample).reason()).isEqualTo(Reason.INVALID_PRICE);
    }

    @Test void missingInteriorAndEndpointsInvalidateEntireSeries() {
        for (var sample : List.of(prices("100", null, "110"), prices(null, "100", "110"), prices("100", "110", null))) {
            for (Value result : List.of(cumulativeReturn(sample), maxDrawdown(sample), annualizedVolatility(sample))) {
                assertThat(result.value()).isNull(); assertThat(result.reason()).isEqualTo(Reason.MISSING_PRICE);
            }
            assertThat(simpleReturns(sample).values()).isEmpty();
        }
        assertThat(simpleReturn(null, BigDecimal.ONE).reason()).isEqualTo(Reason.MISSING_PRICE);
        assertThat(simpleReturn(BigDecimal.ONE, null).reason()).isEqualTo(Reason.MISSING_PRICE);
        assertThat(cumulativeReturn(null).reason()).isEqualTo(Reason.MISSING_PRICE);
        assertThat(simpleReturns(null).reason()).isEqualTo(Reason.MISSING_PRICE);
    }

    @Test void smallSampleFormulaThresholdsAreSeparateFromBusinessMinimumThirty() {
        for (var sample : List.of(prices(), prices("100"))) {
            assertThat(cumulativeReturn(sample).reason()).isEqualTo(Reason.INSUFFICIENT_SAMPLES);
            assertThat(maxDrawdown(sample).reason()).isEqualTo(Reason.INSUFFICIENT_SAMPLES);
            assertThat(simpleReturns(sample).reason()).isEqualTo(Reason.INSUFFICIENT_SAMPLES);
        }
        assertThat(annualizedVolatility(prices("100", "110")).reason()).isEqualTo(Reason.INSUFFICIENT_SAMPLES);
        assertThat(annualizedVolatility(prices("100", "110", "99")).calculable()).isTrue();
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void invalidAnnualizationFactorHasNoFakeValue(int factor) {
        var result = annualizedVolatility(prices("100", "110", "99"), factor);
        assertThat(result.value()).isNull(); assertThat(result.reason()).isEqualTo(Reason.INVALID_ANNUALIZATION_FACTOR);
    }

    @Test void resultRoundingIsHalfUpCanonicalAndNotUsedForIntermediateReturns() {
        assertThat(simpleReturn(BigDecimal.ONE, new BigDecimal("1.00000000005")).value()).isEqualTo("0.0000000001");
        assertThat(simpleReturn(BigDecimal.ONE, new BigDecimal("0.99999999995")).value()).isEqualTo("-0.0000000001");
        assertThat(simpleReturn(new BigDecimal("3"), BigDecimal.ONE).value()).isEqualTo("-0.6666666667");
        assertThat(simpleReturn(BigDecimal.ONE, new BigDecimal("0.999999999999")).value()).isEqualTo("0");
        var sample = prices("100000000000", "100000000004", "100000000000");
        // Each displayed return rounds to zero; raw returns still produce measurable annualized SD.
        assertThat(simpleReturns(sample).values()).containsExactly("0", "0");
        assertThat(annualizedVolatility(sample).value()).isEqualTo("0.0000000009");
    }

    @Test void decimal128HandlesFullContractPriceRangeWithoutDoubleOverflowOrInputMutation() {
        var sample = new ArrayList<>(prices("0.0000000001", "999999999999999999.9999999999", "0.0000000001"));
        var original = List.copyOf(sample);
        assertThat(annualizedVolatility(sample).calculable()).isTrue();
        assertThat(annualizedVolatility(sample).value()).doesNotContain("E", "NaN", "Infinity");
        assertThat(sample).isEqualTo(original);
        assertThat(annualizedVolatility(sample)).isEqualTo(annualizedVolatility(sample));
    }
}
