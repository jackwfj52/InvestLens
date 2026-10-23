package org.jack.backend.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QuantPolicy {
    private final int minimumSamples;
    public QuantPolicy(@Value("${investlens.quant.minimum-return-samples:30}") int minimumSamples) {
        if (minimumSamples < 2) throw new IllegalArgumentException("Volatility requires at least two return samples");
        this.minimumSamples = minimumSamples;
    }
    public int minimumSamples() { return minimumSamples; }
    public record Definition(String policyVersion, int annualizationFactor, int minimumReturnSamples,
                             boolean correlationProvided) { }
    public Definition definition() { return new Definition("quant-policy-v1", 252, minimumSamples, false); }
}
