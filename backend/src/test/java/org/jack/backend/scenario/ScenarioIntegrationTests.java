package org.jack.backend.scenario;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.jack.backend.common.exception.*;
import org.jack.backend.decision.*;
import org.jack.backend.evidence.EvidenceData;
import org.jack.backend.portfolio.PortfolioDtos;
import org.jack.backend.quant.MetricData.Metric;
import org.jack.backend.support.CalculationIntegrationSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import tools.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.jack.backend.scenario.ScenarioData.*;

@Import(ScenarioIntegrationTests.FaultConfiguration.class)
class ScenarioIntegrationTests extends CalculationIntegrationSupport {
    @Autowired ScenarioService scenarios;
    @Autowired ScenarioCalculator calculator;
    @Autowired DecisionService decisions;
    @Autowired ScenarioFault fault;
    final List<String> scenarioIds = new ArrayList<>(), decisionIds = new ArrayList<>();
    record Fixture(PortfolioDtos.Snapshot portfolio, EvidenceData.Snapshot snapshot, DecisionDtos.Version decision, Create request) { }
    List<Shock> shocks() { return List.of(new Shock(GOLD, "-0.2"), new Shock(SILVER, "-0.25"), new Shock(CASH, "0")); }
    ObjectNode decisionDraft(PortfolioDtos.Snapshot p) throws Exception {
        ObjectNode draft = (ObjectNode) json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        draft.put("inputStatus", "CONFIRMED"); ((ObjectNode) draft.get("proposedDecision")).put("portfolioSnapshotId", p.snapshotId());
        return draft;
    }
    DecisionDtos.Version decision(PortfolioDtos.Snapshot p, Consumer<ObjectNode> modify) throws Exception {
        var draft = decisionDraft(p); modify.accept(draft);
        var d = decisions.create(json.treeToValue(draft, DecisionDtos.Draft.class)); decisionIds.add(d.decisionId()); return d;
    }
    Fixture fixture(String total, Consumer<ObjectNode> modify) throws Exception {
        var p = portfolio(total); var s = snapshot(p, List.of()); var d = decision(p, modify);
        return new Fixture(p, s, d, new Create("1.0.0", d.decisionId(), d.version(), s.snapshotId(), p.snapshotId(), shocks()));
    }
    Result create(Create request) {
        var result = scenarios.create(request); scenarioIds.add(result.scenarioId()); evidenceIds.add(result.sourceEvidenceRef()); return result;
    }
    Create withShocks(Fixture f, List<Shock> shocks) { var r = f.request(); return new Create(r.schemaVersion(), r.decisionId(), r.decisionVersion(), r.calculationSnapshotId(), r.portfolioSnapshotId(), shocks); }
    Map<String, Metric> named(Result r) { var values = new HashMap<String, Metric>(); r.metrics().forEach(m -> values.put(m.name(), m)); return values; }
    @AfterEach void cleanScenarioRows() {
        fault.fail.set(false);
        scenarioIds.forEach(id -> jdbc.update("DELETE FROM scenario_results WHERE scenario_id = ?", id));
        decisionIds.forEach(id -> { jdbc.update("DELETE FROM decision_versions WHERE decision_id = ?", id); jdbc.update("DELETE FROM decisions WHERE decision_id = ?", id); });
    }

    @Test void contractExampleSubtractsCashAndReportsMinusSixPointFiveAndEightPointFive() throws Exception {
        var f = fixture(null, d -> { }); var r = create(f.request()); var m = named(r);
        assertThat(m.get("SCENARIO_RETURN_BEFORE").value()).isEqualTo("-0.065");
        assertThat(m.get("SCENARIO_RETURN_AFTER").value()).isEqualTo("-0.085");
        assertThat(m.get("SCENARIO_PNL_BEFORE").value()).isEqualTo("-6.5");
        assertThat(m.get("SCENARIO_PNL_AFTER").value()).isEqualTo("-8.5");
        assertThat(m.get("SCENARIO_RETURN_CHANGE").value()).isEqualTo("-2");
        assertThat(m.get("WEIGHT_CHANGE").value()).isEqualTo("10");
        assertThat(m.get("WEIGHT_CHANGE").unit()).isEqualTo("PERCENTAGE_POINT");
        assertThat(r.afterWeights()).containsExactly(new Weight(CASH, "0.6"), new Weight(GOLD, "0.3"), new Weight(SILVER, "0.1"));
        assertThat(r.ruleResults()).extracting(Rule::status).containsExactly("PASS", "FAIL");
        assertThat(r.pnlUnit()).isEqualTo("BASE_CURRENCY_UNIT");
        assertThat(r.concentration().hhiBefore()).isEqualTo("0.54"); assertThat(r.concentration().hhiAfter()).isEqualTo("0.46");
        assertThat(r.concentration().includesCash()).isTrue();
        assertThat(r.concentration().unit()).isEqualTo("RATIO");
        assertThat(r.metricRefs()).containsExactlyElementsOf(r.metrics().stream().map(Metric::metricId).toList());
        assertThat(portfolios.getSnapshot(f.portfolio().snapshotId())).isEqualTo(f.portfolio());
        assertThat(evidence.evidence(r.sourceEvidenceRef()).type()).isEqualTo("USER_INPUT");
        assertThat(r.metrics()).allMatch(metric -> metric.sampleCount() == 0 && metric.sampleWindow() == null && metric.evidenceRefs().equals(List.of(r.sourceEvidenceRef())));
        assertThat(r.runId()).isNull(); assertThat(r.snapshotHash()).isNull();
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/g14-scenario.json"), json.writeValueAsString(r));
        verifyNoInteractions(provider);
    }
    @Test void deltaPointsAndAbsoluteValuationUseSameArithmeticAndHonestUnits() throws Exception {
        var f = fixture("10000", d -> {
            ObjectNode action = (ObjectNode) d.get("proposedDecision").get("action");
            action.put("allocationMode", "DELTA_PERCENTAGE_POINTS"); action.remove("targetWeight"); action.put("deltaPercentagePoints", "10");
        });
        var r = create(f.request()); var m = named(r);
        assertThat(m.get("SCENARIO_PNL_BEFORE").value()).isEqualTo("-650");
        assertThat(m.get("SCENARIO_PNL_AFTER").value()).isEqualTo("-850");
        assertThat(m.get("SCENARIO_PNL_AFTER").unit()).isEqualTo("CURRENCY"); assertThat(m.get("SCENARIO_PNL_AFTER").currency()).isEqualTo("USD");
        assertThat(m.get("TARGET_WEIGHT_AFTER").value()).isEqualTo("0.3");
    }
    @Test void thresholdEqualityPassesAndProfitHasZeroLoss() throws Exception {
        var f = fixture(null, d -> ((ObjectNode) d.get("proposedDecision").get("constraints")).put("maxScenarioLoss", "0.085"));
        assertThat(create(f.request()).ruleResults()).allMatch(rule -> rule.status().equals("PASS"));
        var profit = create(withShocks(f, List.of(new Shock(CASH, "0"), new Shock(GOLD, "0.2"), new Shock(SILVER, "0.25"))));
        assertThat(profit.ruleResults().get(1).status()).isEqualTo("PASS");
        var targetFail = fixture(null, d -> ((ObjectNode) d.get("proposedDecision").get("constraints")).put("maxTargetWeight", "0.29"));
        assertThat(create(targetFail.request()).ruleResults().get(0).status()).isEqualTo("FAIL");
    }
    @Test void reorderedShocksHaveStableHashWhileChangedAssumptionsDoNotRewriteHistory() throws Exception {
        var f = fixture(null, d -> { }); var first = create(f.request());
        var reverse = new ArrayList<>(shocks()); Collections.reverse(reverse);
        assertThat(create(withShocks(f, reverse)).inputHash()).isEqualTo(first.inputHash());
        var changed = create(withShocks(f, List.of(new Shock(GOLD, "-0.1"), new Shock(SILVER, "-0.25"), new Shock(CASH, "0"))));
        assertThat(changed.inputHash()).isNotEqualTo(first.inputHash());
        assertThat(scenarios.get(first.scenarioId()).metrics()).isEqualTo(first.metrics());
        assertThat(scenarios.get(first.scenarioId()).shocks()).isEqualTo(first.shocks());
    }
    @Test void createAndGetUseRealHttpLocationAndOwnerIsolation() throws Exception {
        var f = fixture(null, d -> { }); var response = send("POST", "/api/scenarios", f.request());
        assertThat(response.statusCode()).isEqualTo(201);
        var data = json.readTree(response.body()).get("data"); var r = json.treeToValue(data, Result.class);
        scenarioIds.add(r.scenarioId()); evidenceIds.add(r.sourceEvidenceRef());
        assertThat(response.headers().firstValue("Location")).contains("/api/scenarios/" + r.scenarioId());
        assertThat(json.readTree(send("GET", "/api/scenarios/" + r.scenarioId(), null).body()).get("data")).isEqualTo(data);
        jdbc.update("UPDATE scenario_results SET owner_id = 'another' WHERE scenario_id = ?", r.scenarioId());
        assertThat(send("GET", "/api/scenarios/" + r.scenarioId(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/scenarios/unknown", null).statusCode()).isEqualTo(404);
    }
    @ParameterizedTest @ValueSource(strings = {"missingCash", "duplicate", "unknown", "tooLow", "tooHigh", "nullShock", "number", "overprecision", "unknownField"})
    void invalidShockRequestsAreRejectedWithoutExternalCalls(String kind) throws Exception {
        var f = fixture(null, d -> { }); ObjectNode request = (ObjectNode) json.valueToTree(f.request());
        ArrayNode values = (ArrayNode) request.get("shocks");
        switch (kind) {
            case "missingCash" -> values.remove(2);
            case "duplicate" -> values.add(values.get(0));
            case "unknown" -> ((ObjectNode) values.get(0)).put("instrumentId", "UNKNOWN");
            case "tooLow" -> ((ObjectNode) values.get(0)).put("shock", "-1.0000000001");
            case "tooHigh" -> ((ObjectNode) values.get(0)).put("shock", "1.0000000001");
            case "nullShock" -> ((ObjectNode) values.get(0)).putNull("shock");
            case "number" -> ((ObjectNode) values.get(0)).put("shock", -0.2);
            case "overprecision" -> ((ObjectNode) values.get(0)).put("shock", "-0.12345678901");
            case "unknownField" -> request.put("targetWeight", "0.5");
        }
        assertThat(send("POST", "/api/scenarios", request).statusCode()).isEqualTo(400);
        verifyNoInteractions(provider);
    }
    @ParameterizedTest @ValueSource(strings = {"noIncrease", "insufficientCash", "nonCashFunding", "unknownTarget", "cashTarget"})
    void invalidReallocationsCannotConsumeOrInventFunds(String kind) throws Exception {
        var f = fixture(null, d -> {
            ObjectNode a = (ObjectNode) d.get("proposedDecision").get("action");
            switch (kind) {
                case "noIncrease" -> a.put("targetWeight", "0.2");
                case "insufficientCash" -> a.put("targetWeight", "1");
                case "nonCashFunding" -> a.put("fundingInstrumentId", SILVER);
                case "unknownTarget" -> a.put("instrumentId", "UNKNOWN");
                case "cashTarget" -> { a.put("instrumentId", CASH); a.put("fundingInstrumentId", GOLD); a.put("targetWeight", "0.8"); }
            }
        });
        assertThat(send("POST", "/api/scenarios", f.request()).statusCode()).isEqualTo(400);
    }
    @Test void mismatchedSnapshotAndUnconfirmedDecisionAreRejected() throws Exception {
        var f = fixture(null, d -> { }); var another = portfolio();
        var request = f.request();
        assertThat(send("POST", "/api/scenarios", new Create("1.0.0", request.decisionId(), request.decisionVersion(), request.calculationSnapshotId(), another.snapshotId(), shocks())).statusCode()).isEqualTo(409);
        var unconfirmed = decision(f.portfolio(), d -> d.put("inputStatus", "READY"));
        assertThat(send("POST", "/api/scenarios", new Create("1.0.0", unconfirmed.decisionId(), unconfirmed.version(), request.calculationSnapshotId(), request.portfolioSnapshotId(), shocks())).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE calculation_snapshots SET owner_id = 'another' WHERE snapshot_id = ?", request.calculationSnapshotId());
        assertThat(send("POST", "/api/scenarios", request).statusCode()).isEqualTo(404);
    }
    @Test void oldDecisionAndPortfolioVersionsStayFixedAfterRevision() throws Exception {
        var f = fixture(null, d -> { }); var first = create(f.request());
        var draft = decisionDraft(f.portfolio());
        ((ObjectNode) draft.get("proposedDecision").get("action")).put("targetWeight", "0.4");
        decisions.revise(f.decision().decisionId(), new DecisionDtos.Revision("1.0.0", 1, json.treeToValue(draft, DecisionDtos.Draft.class), true));
        portfolios.revise(f.portfolio().portfolioId(), new PortfolioDtos.Revision("1.0.0", 1, f.portfolio().positions(), "2026-10-23T01:00:00.000Z"));
        var sameVersion = create(f.request());
        assertThat(sameVersion.inputHash()).isEqualTo(first.inputHash());
        assertThat(named(sameVersion).get("TARGET_WEIGHT_AFTER").value()).isEqualTo("0.3");
    }
    @Test void explicitCashShockIsHonoredAndTotalLossIsAllowed() throws Exception {
        var f = fixture(null, d -> { });
        var cash = create(withShocks(f, List.of(new Shock(GOLD, "0"), new Shock(SILVER, "0"), new Shock(CASH, "0.1"))));
        assertThat(named(cash).get("SCENARIO_RETURN_BEFORE").value()).isEqualTo("0.07");
        assertThat(named(cash).get("SCENARIO_RETURN_AFTER").value()).isEqualTo("0.06");
        var all = create(withShocks(f, List.of(new Shock(GOLD, "-1"), new Shock(SILVER, "-1"), new Shock(CASH, "-1"))));
        assertThat(named(all).get("SCENARIO_RETURN_AFTER").value()).isEqualTo("-1");
    }
    @Test void writeFailureRollsBackUserEvidenceAndItsPayload() throws Exception {
        var f = fixture(null, d -> { });
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class);
        int payloads = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_payloads", Integer.class);
        fault.fail.set(true);
        assertThatThrownBy(() -> scenarios.create(f.request())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence_payloads", Integer.class)).isEqualTo(payloads);
    }
    @Test void exactlyConsumingCashPreservesUnitWeightAndPositiveBoundaryShocksAreAllowed() throws Exception {
        var f = fixture(null, d -> ((ObjectNode) d.get("proposedDecision").get("action")).put("targetWeight", "0.9"));
        var r = create(withShocks(f, List.of(new Shock(GOLD, "1"), new Shock(SILVER, "1"), new Shock(CASH, "1"))));
        assertThat(r.afterWeights()).contains(new Weight(CASH, "0"));
        assertThat(named(r).get("SCENARIO_RETURN_AFTER").value()).isEqualTo("1");
    }
    @Test void malformedSavedWeightsAndCurrencyCannotEnterArithmetic() throws Exception {
        var f = fixture(null, d -> { }); var p = f.portfolio();
        for (String weight : List.of("-0.1", "1.1", "0.21")) {
            var positions = List.of(new PortfolioDtos.Position(GOLD, "USD", weight), new PortfolioDtos.Position(SILVER, "USD", "0.1"), new PortfolioDtos.Position(CASH, "USD", "0.7"));
            var invalid = new PortfolioDtos.Snapshot(p.schemaVersion(), p.snapshotId(), p.portfolioId(), p.version(), p.hashAlgorithm(), p.baseCurrency(), p.valuationBasis(), p.totalValue(), p.asOf(), p.dataMode(), positions, p.contentHash());
            assertThatThrownBy(() -> calculator.calculate(invalid, f.decision().action(), shocks())).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_WEIGHTS));
        }
        var positions = List.of(new PortfolioDtos.Position(GOLD, "EUR", "0.2"), new PortfolioDtos.Position(SILVER, "USD", "0.1"), new PortfolioDtos.Position(CASH, "USD", "0.7"));
        var invalid = new PortfolioDtos.Snapshot(p.schemaVersion(), p.snapshotId(), p.portfolioId(), p.version(), p.hashAlgorithm(), p.baseCurrency(), p.valuationBasis(), p.totalValue(), p.asOf(), p.dataMode(), positions, p.contentHash());
        assertThatThrownBy(() -> calculator.calculate(invalid, f.decision().action(), shocks())).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    }
    @TestConfiguration static class FaultConfiguration { @Bean ScenarioFault scenarioFault() { return new ScenarioFault(); } }
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class ScenarioFault implements Interceptor {
        final AtomicBoolean fail = new AtomicBoolean();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement) invocation.getArgs()[0]).getId().equals(ScenarioMapper.class.getName() + ".insert") && fail.getAndSet(false)) throw new IllegalStateException("Synthetic scenario persistence fault");
            return invocation.proceed();
        }
    }
}
