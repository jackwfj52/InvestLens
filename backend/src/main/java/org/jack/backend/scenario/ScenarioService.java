package org.jack.backend.scenario;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.decision.DecisionService;
import org.jack.backend.evidence.*;
import org.jack.backend.market.MarketQuality.Quality;
import org.jack.backend.portfolio.*;
import org.jack.backend.quant.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.jack.backend.evidence.EvidenceData.ENGINE_VERSION;
import static org.jack.backend.quant.MetricFactory.decimal;
import static org.jack.backend.scenario.ScenarioData.*;

@Service
@Validated
public class ScenarioService {
    private final DecisionService decisions;
    private final PortfolioService portfolios;
    private final EvidenceService evidence;
    private final EvidenceMapper evidenceMapper;
    private final ScenarioCalculator calculator;
    private final ScenarioMapper mapper;
    private final ObjectMapper json;
    private final CurrentOwner owner;
    private final MetricFactory factory;
    public ScenarioService(DecisionService decisions, PortfolioService portfolios, EvidenceService evidence,
                           EvidenceMapper evidenceMapper, ScenarioCalculator calculator, ScenarioMapper mapper,
                           ObjectMapper json, CurrentOwner owner, MetricFactory factory) {
        this.decisions = decisions; this.portfolios = portfolios; this.evidence = evidence; this.evidenceMapper = evidenceMapper;
        this.calculator = calculator; this.mapper = mapper; this.json = json; this.owner = owner; this.factory = factory;
    }
    @Transactional
    public Result create(@NotNull @Valid Create request) {
        var decision = decisions.requireConfirmed(request.decisionId(), request.decisionVersion());
        var snapshot = evidence.snapshot(request.calculationSnapshotId());
        var p = portfolios.getSnapshot(request.portfolioSnapshotId());
        if (!p.snapshotId().equals(decision.portfolioSnapshotId()) || !p.snapshotId().equals(snapshot.portfolioRef().snapshotId())
                || p.version() != snapshot.portfolioRef().version() || !p.contentHash().equals(snapshot.portfolioRef().contentHash())
                || !p.baseCurrency().equals(decision.baseCurrency()) || p.dataMode() != snapshot.dataMode()
                || !ENGINE_VERSION.equals(snapshot.engineVersion())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        var c = calculator.calculate(p, decision.action(), request.shocks());
        var parameters = Map.<String, Object>of("dataMode", p.dataMode().name(), "valuationBasis", p.valuationBasis().name(),
                "costsIncluded", false, "constraints", decision.constraints());
        String hash = CanonicalJson.sha256(json.valueToTree(new EvidenceData.InputContent("1.0.0", ENGINE_VERSION,
                p.contentHash(), decision.action(), c.shocks().stream().map(s -> (Object) s).toList(), List.of(), parameters)));
        String sourceId = "evidence_" + UUID.randomUUID();
        var decisionRef = new DecisionRef(decision.decisionId(), decision.version());
        var userContent = Map.of("schemaVersion", "1.0.0", "portfolioRef", snapshot.portfolioRef(), "decisionRef", decisionRef,
                "action", decision.action(), "constraints", decision.constraints(), "shocks", c.shocks());
        String userHash = CanonicalJson.sha256(json.valueToTree(userContent));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String timestamp = org.jack.backend.market.MarketData.timestamp(now);
        LocalDateTime dbNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var source = new EvidenceData.Evidence("1.0.0", sourceId, null, null, "USER_INPUT", "USER_INPUT", null,
                null, p.asOf(), timestamp, Quality.VALID, p.dataMode(), userHash, EvidenceData.StoragePolicy.PAYLOAD_ALLOWED,
                sourceId, true, List.of(), List.of());
        evidenceMapper.insertEvidence(sourceId, owner.id(), source.storagePolicy().name(), json.writeValueAsString(source), dbNow);
        if (evidenceMapper.insertPayload(sourceId, json.writeValueAsString(userContent)) != 1) throw new BusinessException(ErrorCode.INVALID_STATE);
        String pnlUnit = p.valuationBasis() == PortfolioDtos.ValuationBasis.NORMALIZED ? "BASE_CURRENCY_UNIT" : "CURRENCY";
        var params = new MetricData.Parameters(null, null, p.valuationBasis(), false);
        var metrics = new ArrayList<MetricData.Metric>();
        metrics.add(metric("WEIGHT_CHANGE", c.deltaWeight().movePointRight(2), "PERCENTAGE_POINT", null, hash, "weight-change-v1", params, sourceId));
        metrics.add(metric("TARGET_WEIGHT_AFTER", c.targetAfter(), "RATIO", null, hash, "weight-after-v1", params, sourceId));
        metrics.add(metric("SCENARIO_RETURN_BEFORE", c.returnBefore(), "RATIO", null, hash, ScenarioCalculator.SHOCK_VERSION, params, sourceId));
        metrics.add(metric("SCENARIO_RETURN_AFTER", c.returnAfter(), "RATIO", null, hash, ScenarioCalculator.SHOCK_VERSION, params, sourceId));
        metrics.add(metric("SCENARIO_PNL_BEFORE", c.pnlBefore(), pnlUnit, p.baseCurrency(), hash, ScenarioCalculator.SHOCK_VERSION, params, sourceId));
        metrics.add(metric("SCENARIO_PNL_AFTER", c.pnlAfter(), pnlUnit, p.baseCurrency(), hash, ScenarioCalculator.SHOCK_VERSION, params, sourceId));
        metrics.add(metric("SCENARIO_RETURN_CHANGE", c.returnAfter().subtract(c.returnBefore()).movePointRight(2), "PERCENTAGE_POINT", null, hash, ScenarioCalculator.SHOCK_VERSION, params, sourceId));
        var rules = List.of(rule("MAX_TARGET_WEIGHT", c.targetAfter(), decision.constraints().maxTargetWeight(), metrics.get(1).metricId()),
                rule("MAX_SCENARIO_LOSS", c.returnAfter().negate().max(BigDecimal.ZERO), decision.constraints().maxScenarioLoss(), metrics.get(3).metricId()));
        var result = new Result("1.0.0", "scenario_" + UUID.randomUUID(), null, null, null, snapshot.snapshotId(), decisionRef,
                snapshot.portfolioRef(), hash, ENGINE_VERSION, p.dataMode(), timestamp, sourceId, pnlUnit, p.baseCurrency(),
                decision.action(), decision.constraints(), c.before(), c.after(), c.shocks(), c.impacts(), c.concentration(), metrics.stream().map(MetricData.Metric::metricId).toList(), metrics,
                factory.hashes(metrics), rules, List.of("冲击值是用户设定的直接价格变化假设，不代表预测",
                "现金冲击由用户显式提供", "不含交易成本、税费、滑点、融资和外部资金流", "规则评估不验证宏观因果假设"));
        mapper.insert(result.scenarioId(), owner.id(), snapshot.snapshotId(), p.snapshotId(), decision.decisionId(), decision.version(),
                sourceId, hash, json.writeValueAsString(result), dbNow);
        return result;
    }
    @Transactional(readOnly = true)
    public Result get(String id) {
        String stored = mapper.find(id, owner.id());
        if (stored == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return json.readValue(stored, Result.class);
    }
    private MetricData.Metric metric(String name, BigDecimal value, String unit, String currency, String hash,
                                     String formula, MetricData.Parameters params, String sourceId) {
        return factory.create(name, decimal(value), unit, currency, hash, formula, params, null, 0, Quality.VALID, null, List.of(sourceId));
    }
    private static Rule rule(String id, BigDecimal actual, String threshold, String metricId) {
        boolean pass = actual.compareTo(new BigDecimal(threshold)) <= 0;
        return new Rule(id, pass ? "PASS" : "FAIL", threshold, "RATIO", List.of(metricId), pass ? null : id + "_EXCEEDED");
    }
}
