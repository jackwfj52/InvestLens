package org.jack.backend.quant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.evidence.*;
import org.jack.backend.market.MarketQuality.Quality;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.jack.backend.quant.MetricData.*;
import static org.jack.backend.quant.FinancialFormulas.*;
import static org.jack.backend.evidence.EvidenceData.ENGINE_VERSION;

@Service
@Validated
public class MetricService {
    private final EvidenceService evidence;
    private final MetricMapper mapper;
    private final ObjectMapper json;
    private final CurrentOwner owner;
    private final QuantPolicy policy;
    private final MetricFactory factory;
    public MetricService(EvidenceService evidence, MetricMapper mapper, ObjectMapper json, CurrentOwner owner,
                         QuantPolicy policy, MetricFactory factory) {
        this.evidence = evidence; this.mapper = mapper; this.json = json; this.owner = owner; this.policy = policy; this.factory = factory;
    }
    @Transactional
    public Bundle calculate(@NotNull @Valid Create request) {
        var replay = evidence.replay(request.calculationSnapshotId());
        if (!ENGINE_VERSION.equals(replay.snapshot().engineVersion())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        if (replay.marketInputs().isEmpty()) throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        var parameters = Map.<String, Object>of("dataMode", replay.snapshot().dataMode().name(),
                "valuationBasis", replay.portfolio().valuationBasis().name(), "costsIncluded", false,
                "annualizationFactor", 252, "minimumSamples", policy.minimumSamples());
        String hash = CanonicalJson.sha256(json.valueToTree(new EvidenceData.InputContent("1.0.0", ENGINE_VERSION,
                replay.portfolio().contentHash(), null, List.of(), replay.marketInputs(), parameters)));
        var metrics = new ArrayList<Metric>();
        var assets = new ArrayList<AssetMetrics>();
        for (int i = 0; i < replay.marketInputs().size(); i++) {
            var content = replay.marketInputs().get(i);
            var source = replay.snapshot().marketInputs().get(i);
            List<BigDecimal> prices = content.bars().stream().map(b -> new BigDecimal(b.close())).toList();
            var window = new Window(content.bars().get(0).sessionDate(), content.bars().get(prices.size() - 1).sessionDate(), "1d");
            List<String> refs = List.of(source.evidenceRef());
            var period = metric("PERIOD_RETURN", cumulativeReturn(prices), hash, RETURN_VERSION,
                    new Parameters(null, 2, null, false), window, prices.size(), refs);
            var drawdown = metric("MAX_DRAWDOWN", maxDrawdown(prices), hash, DRAWDOWN_VERSION,
                    new Parameters(null, 2, null, false), window, prices.size(), refs);
            int count = prices.size() - 1;
            Value volatility = count < policy.minimumSamples() ? new Value(null, Reason.INSUFFICIENT_SAMPLES)
                    : annualizedVolatility(prices, 252);
            var vol = metric("ANNUALIZED_VOLATILITY", volatility, hash, VOLATILITY_VERSION,
                    new Parameters(252, policy.minimumSamples(), null, false), window, count, refs);
            metrics.addAll(List.of(period, vol, drawdown));
            assets.add(new AssetMetrics(content.instrument().instrumentId(), List.of(period.metricId(), vol.metricId(), drawdown.metricId())));
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var bundle = new Bundle("1.0.0", "bundle_" + UUID.randomUUID(), null, null, null,
                replay.snapshot().snapshotId(), replay.snapshot().inputHash(), hash, ENGINE_VERSION, replay.snapshot().dataMode(),
                org.jack.backend.market.MarketData.timestamp(now), assets, metrics, factory.hashes(metrics));
        mapper.insert(bundle.bundleId(), owner.id(), bundle.calculationSnapshotId(), hash, json.writeValueAsString(bundle), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return bundle;
    }
    @Transactional(readOnly = true)
    public Bundle get(String id) {
        String stored = mapper.find(id, owner.id());
        if (stored == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return json.readValue(stored, Bundle.class);
    }
    private Metric metric(String name, Value value, String hash, String formula, Parameters params,
                          Window window, int count, List<String> refs) {
        return factory.create(name, value.value(), "RATIO", null, hash, formula, params, window, count,
                value.calculable() ? Quality.VALID : Quality.MISSING, value.reason() == null ? null : value.reason().name(), refs);
    }
}
