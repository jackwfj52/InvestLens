package org.jack.backend.scenario;

import jakarta.validation.Validator;
import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.validation.ContractPatterns;
import org.jack.backend.decision.DecisionDtos.*;
import org.jack.backend.portfolio.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static org.jack.backend.scenario.ScenarioData.*;
import static org.jack.backend.portfolio.PortfolioDtos.Snapshot;
import static org.jack.backend.quant.MetricFactory.decimal;

/** No IO. Cash is an explicitly registered identity, never inferred from the spelling of an ID. */
@Component
public class ScenarioCalculator {
    public static final String SHOCK_VERSION = "direct-shock-v1";
    private final PortfolioValidator portfolios;
    private final PortfolioInstrumentCatalog instruments;
    private final Validator validator;
    public ScenarioCalculator(PortfolioValidator portfolios, PortfolioInstrumentCatalog instruments, Validator validator) {
        this.portfolios = portfolios; this.instruments = instruments; this.validator = validator;
    }
    public record Calculation(List<Weight> before, List<Weight> after, List<Shock> shocks, List<Impact> impacts,
                              BigDecimal deltaWeight, BigDecimal targetAfter, BigDecimal returnBefore,
                              BigDecimal returnAfter, BigDecimal pnlBefore, BigDecimal pnlAfter, Concentration concentration) { }
    public Calculation calculate(Snapshot p, Action action, List<Shock> shocks) {
        if (action == null || !validator.validate(action).isEmpty()) fail(ErrorCode.VALIDATION_ERROR, "action");
        if (shocks == null || shocks.stream().anyMatch(s -> s == null || !validator.validate(s).isEmpty())) fail(ErrorCode.VALIDATION_ERROR, "shocks");
        for (var position : p.positions()) {
            if (position.weight() == null || !position.weight().matches(ContractPatterns.RATIO)) fail(ErrorCode.INVALID_WEIGHTS, "positions");
        }
        portfolios.check(p.baseCurrency(), p.valuationBasis(), p.totalValue(), p.asOf(), p.dataMode(), p.positions());
        var before = new TreeMap<String, BigDecimal>();
        p.positions().forEach(position -> before.put(position.instrumentId(), new BigDecimal(position.weight())));
        if (action.instrumentId().equals(action.fundingInstrumentId()) || !before.containsKey(action.instrumentId()) || !before.containsKey(action.fundingInstrumentId())) {
            fail(ErrorCode.UNSUPPORTED_INSTRUMENT, "action");
        }
        if (instruments.require(action.instrumentId(), p.dataMode(), "action.instrumentId").kind() == PortfolioInstrumentCatalog.Kind.CASH
                || instruments.require(action.fundingInstrumentId(), p.dataMode(), "action.fundingInstrumentId").kind() != PortfolioInstrumentCatalog.Kind.CASH) {
            fail(ErrorCode.INSUFFICIENT_FUNDING, "action.fundingInstrumentId");
        }
        BigDecimal original = before.get(action.instrumentId());
        BigDecimal target;
        if (action.allocationMode() == AllocationMode.TARGET_WEIGHT) {
            if (action.targetWeight() == null || action.deltaPercentagePoints() != null) fail(ErrorCode.VALIDATION_ERROR, "action.targetWeight");
            target = new BigDecimal(action.targetWeight());
        } else {
            if (action.deltaPercentagePoints() == null || action.targetWeight() != null) fail(ErrorCode.VALIDATION_ERROR, "action.deltaPercentagePoints");
            BigDecimal points = new BigDecimal(action.deltaPercentagePoints());
            if (points.signum() <= 0 || points.compareTo(new BigDecimal("100")) > 0) fail(ErrorCode.INVALID_WEIGHTS, "action.deltaPercentagePoints");
            target = original.add(points.movePointLeft(2));
            // Domain weights have a fixed ten-place contract; never silently round a more precise reallocation.
            if (target.stripTrailingZeros().scale() > 10) fail(ErrorCode.INVALID_WEIGHTS, "action.deltaPercentagePoints");
        }
        BigDecimal delta = target.subtract(original);
        if (delta.signum() <= 0 || target.compareTo(BigDecimal.ONE) > 0) fail(ErrorCode.INVALID_WEIGHTS, "action");
        BigDecimal cashAfter = before.get(action.fundingInstrumentId()).subtract(delta);
        if (cashAfter.signum() < 0) fail(ErrorCode.INSUFFICIENT_FUNDING, "action.fundingInstrumentId");
        var after = new TreeMap<>(before);
        after.put(action.instrumentId(), target); after.put(action.fundingInstrumentId(), cashAfter);
        var shockMap = new TreeMap<String, BigDecimal>();
        for (Shock shock : shocks) {
            BigDecimal value = new BigDecimal(shock.shock());
            if (!before.containsKey(shock.instrumentId())) fail(ErrorCode.UNSUPPORTED_INSTRUMENT, "shocks");
            if (value.abs().compareTo(BigDecimal.ONE) > 0 || shockMap.putIfAbsent(shock.instrumentId(), value) != null) fail(ErrorCode.VALIDATION_ERROR, "shocks");
        }
        if (!shockMap.keySet().equals(before.keySet())) fail(ErrorCode.VALIDATION_ERROR, "shocks");
        BigDecimal total = new BigDecimal(p.totalValue()), rb = BigDecimal.ZERO, ra = BigDecimal.ZERO;
        var impacts = new ArrayList<Impact>();
        for (String id : before.keySet()) {
            BigDecimal shock = shockMap.get(id), b = before.get(id).multiply(shock), a = after.get(id).multiply(shock);
            rb = rb.add(b); ra = ra.add(a);
            impacts.add(new Impact(id, decimal(before.get(id)), decimal(after.get(id)), decimal(shock), decimal(total.multiply(b)), decimal(total.multiply(a))));
        }
        return new Calculation(weights(before), weights(after), shockMap.entrySet().stream().map(e -> new Shock(e.getKey(), decimal(e.getValue()))).toList(),
                impacts, delta, target, rb, ra, total.multiply(rb), total.multiply(ra),
                new Concentration("weight-concentration-v1", "RATIO", true, decimal(max(before)), decimal(max(after)), decimal(hhi(before)), decimal(hhi(after))));
    }
    private static List<Weight> weights(Map<String, BigDecimal> values) { return values.entrySet().stream().map(e -> new Weight(e.getKey(), decimal(e.getValue()))).toList(); }
    private static BigDecimal max(Map<String, BigDecimal> values) { return values.values().stream().max(BigDecimal::compareTo).orElseThrow(); }
    private static BigDecimal hhi(Map<String, BigDecimal> values) { return values.values().stream().map(w -> w.multiply(w)).reduce(BigDecimal.ZERO, BigDecimal::add); }
    private static void fail(ErrorCode code, String field) { throw new BusinessException(code, List.of(ApiError.invalid(field))); }
}
