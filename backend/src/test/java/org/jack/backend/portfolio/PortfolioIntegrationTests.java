package org.jack.backend.portfolio;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.decision.DecisionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(PortfolioIntegrationTests.FaultConfiguration.class)
class PortfolioIntegrationTests {
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PortfolioService service;
    @Autowired DecisionService decisions;
    @Autowired SnapshotInsertFault fault;
    @LocalServerPort int port;
    private final List<String> ids = new ArrayList<>();
    private final List<String> decisionIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @AfterEach
    void cleanOwnRows() {
        fault.failNext.set(false);
        for (String id : decisionIds) {
            jdbc.update("DELETE FROM decision_versions WHERE decision_id = ?", id);
            jdbc.update("DELETE FROM decisions WHERE decision_id = ?", id);
        }
        for (String id : ids) {
            jdbc.update("DELETE FROM portfolio_snapshots WHERE portfolio_id = ?", id);
            jdbc.update("DELETE FROM portfolios WHERE portfolio_id = ?", id);
        }
    }

    @Test
    void g01WeightsAndHashMatchAndContentIgnoresIdsNameAndPositionOrder() throws Exception {
        ObjectNode request = example();
        Result first = create(request);
        assertThat(first.data().get("contentHash").asString()).isEqualTo("sha256:40e2b229e37f3fcb400a0e3d26db1f0d9826af57518d5b769388b5ac5aa81a54");
        assertThat(first.location()).isEqualTo("/api/portfolios/" + first.id() + "?version=1");
        assertThat(first.body().get("traceId").asString()).isEqualTo(first.traceId());
        assertThat(first.data().has("name")).isFalse();
        assertThat(first.data().get("totalValue").asString()).isEqualTo("100");
        assertThat(send("GET", first.location(), null).data()).isEqualTo(first.data());
        request.put("name", "另一组合，同一内容");
        ArrayNode original = (ArrayNode) request.get("positions");
        ArrayNode reversed = json.createArrayNode().add(original.get(2)).add(original.get(1)).add(original.get(0));
        request.set("positions", reversed);
        Result second = create(request);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.data().get("snapshotId")).isNotEqualTo(first.data().get("snapshotId"));
        assertThat(second.data().get("contentHash")).isEqualTo(first.data().get("contentHash"));
        assertThat(jdbc.queryForObject("SELECT name FROM portfolios WHERE portfolio_id = ?", String.class, second.id())).isEqualTo(request.get("name").asString());
    }

    @Test
    void revisionPreservesOldSnapshotAndDecisionReference() throws Exception {
        Result first = create(example());
        String snapshotId = first.data().get("snapshotId").asString();
        ObjectNode draft = (ObjectNode) json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        ((ObjectNode) draft.get("proposedDecision")).put("portfolioSnapshotId", snapshotId);
        Result decision = send("POST", "/api/decisions", draft);
        assertThat(decision.status()).isEqualTo(201);
        String decisionId = decision.data().get("decisionId").asString();
        decisionIds.add(decisionId);
        ObjectNode revision = revision(example(), 1);
        weight(revision, 0, "0.3"); weight(revision, 2, "0.6");
        Result updated = send("PATCH", "/api/portfolios/" + first.id(), revision);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().get("version").asInt()).isEqualTo(2);
        assertThat(updated.data().get("snapshotId")).isNotEqualTo(first.data().get("snapshotId"));
        assertThat(updated.data().get("contentHash")).isNotEqualTo(first.data().get("contentHash"));
        assertThat(send("GET", "/api/portfolios/" + first.id(), null).data()).isEqualTo(updated.data());
        assertThat(send("GET", first.location(), null).data()).isEqualTo(first.data());
        JsonNode originalSnapshot = json.valueToTree(service.getSnapshot(snapshotId));
        assertThat(originalSnapshot).isEqualTo(first.data());
        assertThat(decisions.get(decisionId, 1).portfolioSnapshotId()).isEqualTo(snapshotId);
    }

    @Test
    void exactDecimalsThreeNonCashAndExplicitZeroCashAreSupported() throws Exception {
        ObjectNode request = example();
        weight(request, 0, "0.3333333333"); weight(request, 1, "0.3333333333"); weight(request, 2, "0.3333333334");
        request.put("valuationBasis", "ABSOLUTE").put("totalValue", "999999999999999999.1234567891");
        positions(request).addObject().put("instrumentId", "STOCK:SYNTHETIC:DEMO_STOCK:USD").put("quoteCurrency", "USD").put("weight", "0");
        Result saved = create(request);
        assertThat(saved.data().get("totalValue").asString()).isEqualTo("999999999999999999.1234567891");
        assertThat(saved.data().get("positions").size()).isEqualTo(4);
        assertThat(send("GET", saved.location(), null).data()).isEqualTo(saved.data());
        ObjectNode noCashBalance = example();
        weight(noCashBalance, 0, "0.9"); weight(noCashBalance, 2, "0");
        assertThat(create(noCashBalance).status()).isEqualTo(201);
    }

    @Test
    void unchangedContentKeepsHashAndAsOfOrModeChangesHash() throws Exception {
        Result first = create(example());
        Result same = send("PATCH", "/api/portfolios/" + first.id(), revision(example(), 1));
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.data().get("contentHash")).isEqualTo(first.data().get("contentHash"));
        ObjectNode next = revision(example(), 2).put("asOf", "2026-10-23T00:00:00.001Z");
        assertThat(send("PATCH", "/api/portfolios/" + first.id(), next).data().get("contentHash"))
                .isNotEqualTo(first.data().get("contentHash"));
        ObjectNode cashOnly = example();
        cashOnly.putArray("positions").addObject().put("instrumentId", "CASH:USD").put("quoteCurrency", "USD").put("weight", "1");
        Result synthetic = create(cashOnly);
        cashOnly.put("dataMode", "LIVE");
        assertThat(create(cashOnly).data().get("contentHash")).isNotEqualTo(synthetic.data().get("contentHash"));
    }

    @Test
    void twoHttpWritersYieldOneCommitAndOneVersionConflict() throws Exception {
        Result first = create(example());
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch waiting = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            var attempts = Stream.of("2026-10-23T01:00:00.000Z", "2026-10-23T02:00:00.000Z").map(time -> pool.submit(() -> {
                ObjectNode request = revision(example(), 1).put("asOf", time);
                waiting.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return send("PATCH", "/api/portfolios/" + first.id(), request);
            })).toList();
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results = List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
            Result loser = results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
            assertThat(loser.body().get("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_snapshots WHERE portfolio_id = ?", Integer.class, first.id())).isEqualTo(2);
            assertThat(send("GET", first.location(), null).data()).isEqualTo(first.data());
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void snapshotInsertFailureRollsBackHeadAndLeavesRetryPossible() throws Exception {
        Result first = create(example());
        fault.failNext.set(true);
        Result failed = send("PATCH", "/api/portfolios/" + first.id(), revision(example(), 1));
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body().toString()).doesNotContain("injected-snapshot-failure");
        assertThat(send("GET", "/api/portfolios/" + first.id(), null).data()).isEqualTo(first.data());
        assertThat(send("PATCH", "/api/portfolios/" + first.id(), revision(example(), 1)).status()).isEqualTo(200);
        fault.failNext.set(true);
        assertThat(send("POST", "/api/portfolios", example()).status()).isEqualTo(500);
        String rolledBack = fault.failedId.get();
        ids.add(rolledBack);
        assertThat(rolledBack).isNotNull().isNotEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolios WHERE portfolio_id = ?", Integer.class, rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolio_snapshots WHERE portfolio_id = ?", Integer.class, rolledBack)).isZero();
    }

    @Test
    void versionAndSnapshotLookupsAreOwnerScopedAndCaseSensitive() throws Exception {
        Result first = create(example());
        assertThat(send("PATCH", "/api/portfolios/" + first.id(), revision(example(), 2)).status()).isEqualTo(409);
        assertThat(send("GET", "/api/portfolios/not-found", null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/portfolios/" + first.id() + "?version=99", null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/portfolios/" + first.id().toUpperCase(java.util.Locale.ROOT), null).status()).isEqualTo(404);
        for (String version : List.of("0", "-1", "oops")) {
            assertThat(send("GET", "/api/portfolios/" + first.id() + "?version=" + version, null).status()).isEqualTo(400);
        }
        jdbc.update("UPDATE portfolios SET owner_id='other-test-owner' WHERE portfolio_id=?", first.id());
        assertThat(send("GET", first.location(), null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/portfolios/" + first.id(), null).status()).isEqualTo(404);
        assertThat(send("PATCH", "/api/portfolios/" + first.id(), revision(example(), 1)).status()).isEqualTo(404);
        assertThatThrownBy(() -> service.getSnapshot(first.data().get("snapshotId").asString()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ParameterizedTest(name = "reject {0}")
    @MethodSource("invalidCreates")
    void invalidCreateIs400AndWritesNothing(String name, Consumer<ObjectNode> change, String code) throws Exception {
        ObjectNode request = example();
        change.accept(request);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM portfolios", Integer.class);
        Result result = send("POST", "/api/portfolios", request);
        assertThat(result.status()).as(name).isEqualTo(400);
        assertThat(result.body().get("code").asString()).isEqualTo(code);
        assertThat(result.body().get("data").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portfolios", Integer.class)).isEqualTo(before);
    }

    static Stream<Arguments> invalidCreates() {
        return Stream.of(
                bad("sum too high", p -> weight(p, 2, "0.8"), "INVALID_WEIGHTS"),
                bad("sum too low", p -> weight(p, 2, "0.6"), "INVALID_WEIGHTS"),
                bad("no positive tolerance", p -> weight(p, 2, "0.7000000001"), "INVALID_WEIGHTS"),
                bad("no negative tolerance", p -> weight(p, 2, "0.6999999999"), "INVALID_WEIGHTS"),
                bad("negative weight", p -> weight(p, 0, "-0.1"), "VALIDATION_ERROR"),
                bad("mixed currency", p -> position(p, 0).put("quoteCurrency", "EUR"), "CURRENCY_MISMATCH"),
                bad("base currency outside P0", p -> p.put("baseCurrency", "CNY"), "CURRENCY_MISMATCH"),
                bad("duplicate instrument", p -> position(p, 1).put("instrumentId", position(p, 0).get("instrumentId").asString()), "INVALID_WEIGHTS"),
                bad("missing cash funding", p -> { positions(p).remove(2); weight(p, 0, "0.9"); }, "INSUFFICIENT_FUNDING"),
                bad("unknown instrument", p -> position(p, 0).put("instrumentId", "ETF:UNKNOWN:XXX:USD"), "UNSUPPORTED_INSTRUMENT"),
                bad("synthetic in live", p -> p.put("dataMode", "LIVE"), "UNSUPPORTED_INSTRUMENT"),
                bad("cash prefix is not identity", p -> position(p, 2).put("instrumentId", "CASH:USD:FORGED"), "UNSUPPORTED_INSTRUMENT"),
                bad("number weight", p -> position(p, 0).put("weight", 0.2), "INVALID_REQUEST"),
                bad("trailing zero", p -> weight(p, 0, "0.20"), "VALIDATION_ERROR"),
                bad("excess precision", p -> weight(p, 0, "0.12345678901"), "VALIDATION_ERROR"),
                bad("exponent weight", p -> weight(p, 0, "2e-1"), "VALIDATION_ERROR"),
                bad("wrong normalized value", p -> p.put("totalValue", "1000"), "VALIDATION_ERROR"),
                bad("zero absolute value", p -> p.put("valuationBasis", "ABSOLUTE").put("totalValue", "0"), "VALIDATION_ERROR"),
                bad("too many integer digits", p -> p.put("valuationBasis", "ABSOLUTE").put("totalValue", "1000000000000000000"), "VALIDATION_ERROR"),
                bad("number total", p -> p.put("totalValue", 100), "INVALID_REQUEST"),
                bad("invalid calendar date", p -> p.put("asOf", "2026-02-30T00:00:00.000Z"), "VALIDATION_ERROR"),
                bad("year zero", p -> p.put("asOf", "0000-01-01T00:00:00.000Z"), "VALIDATION_ERROR"),
                bad("silent leap second normalization", p -> p.put("asOf", "2026-10-23T00:00:60.000Z"), "VALIDATION_ERROR"),
                bad("UTC offset not Z", p -> p.put("asOf", "2026-10-23T00:00:00.000+00:00"), "VALIDATION_ERROR"),
                bad("missing millis", p -> p.put("asOf", "2026-10-23T00:00:00Z"), "VALIDATION_ERROR"),
                bad("numeric mode", p -> p.put("dataMode", 0), "INVALID_REQUEST"),
                bad("unknown owner", p -> p.put("ownerId", "forged"), "INVALID_REQUEST"),
                bad("client hash", p -> p.put("contentHash", "sha256:forged"), "INVALID_REQUEST"),
                bad("missing positions", p -> p.remove("positions"), "VALIDATION_ERROR"),
                bad("empty positions", p -> p.putArray("positions"), "VALIDATION_ERROR"),
                bad("null position", p -> positions(p).set(0, jsonNull()), "VALIDATION_ERROR"),
                bad("too many positions", p -> { positions(p).add(position(p, 0).deepCopy()); positions(p).add(position(p, 0).deepCopy()); }, "VALIDATION_ERROR")
        );
    }

    @ParameterizedTest(name = "reject revision {0}")
    @MethodSource("invalidRevisions")
    void invalidRevisionLeavesOriginalSnapshot(String name, Consumer<ObjectNode> change) throws Exception {
        Result first = create(example());
        ObjectNode request = revision(example(), 1);
        change.accept(request);
        assertThat(send("PATCH", "/api/portfolios/" + first.id(), request).status()).as(name).isEqualTo(400);
        assertThat(send("GET", "/api/portfolios/" + first.id(), null).data()).isEqualTo(first.data());
    }

    static Stream<Arguments> invalidRevisions() {
        return Stream.of(
                Arguments.of("missing version", (Consumer<ObjectNode>) p -> p.remove("expectedVersion")),
                Arguments.of("string version", (Consumer<ObjectNode>) p -> p.put("expectedVersion", "1")),
                Arguments.of("zero version", (Consumer<ObjectNode>) p -> p.put("expectedVersion", 0)),
                Arguments.of("mode change", (Consumer<ObjectNode>) p -> p.put("dataMode", "LIVE")),
                Arguments.of("invalid weights", (Consumer<ObjectNode>) p -> weight(p, 2, "0.6")),
                Arguments.of("invalid asOf", (Consumer<ObjectNode>) p -> p.put("asOf", "2026-02-30T00:00:00.000Z"))
        );
    }

    private static JsonNode jsonNull() { return tools.jackson.databind.node.NullNode.getInstance(); }
    private static Arguments bad(String name, Consumer<ObjectNode> change, String code) { return Arguments.of(name, change, code); }
    private static ArrayNode positions(ObjectNode request) { return (ArrayNode) request.get("positions"); }
    private static ObjectNode position(ObjectNode request, int index) { return (ObjectNode) positions(request).get(index); }
    private static void weight(ObjectNode request, int index, String weight) { position(request, index).put("weight", weight); }
    private ObjectNode example() throws Exception { return (ObjectNode) json.readTree(getClass().getResourceAsStream("/portfolio-create.json")); }
    private ObjectNode revision(ObjectNode source, int expected) {
        ObjectNode request = json.createObjectNode().put("schemaVersion", "1.0.0").put("expectedVersion", expected)
                .put("asOf", source.get("asOf").asString());
        request.set("positions", source.get("positions").deepCopy());
        return request;
    }
    private Result create(ObjectNode request) throws Exception {
        Result result = send("POST", "/api/portfolios", request);
        if (result.status() == 201) ids.add(result.id());
        assertThat(result.status()).as(result.body().toString()).isEqualTo(201);
        return result;
    }
    private Result send(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").header("X-Owner-Id", "untrusted");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), json.readTree(response.body()), response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("X-Trace-Id").orElse(null));
    }
    private record Result(int status, JsonNode body, String location, String traceId) {
        JsonNode data() { return body.get("data"); }
        String id() { return data().get("portfolioId").asString(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean SnapshotInsertFault snapshotInsertFault() { return new SnapshotInsertFault(); }
    }
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class SnapshotInsertFault implements Interceptor {
        final AtomicBoolean failNext = new AtomicBoolean();
        final AtomicReference<String> failedId = new AtomicReference<>();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement) invocation.getArgs()[0]).getId().equals(PortfolioMapper.class.getName() + ".insertSnapshot")
                    && failNext.compareAndSet(true, false)) {
                failedId.set((String) ((Map<?, ?>) invocation.getArgs()[1]).get("id"));
                throw new DataIntegrityViolationException("injected-snapshot-failure");
            }
            return invocation.proceed();
        }
    }
}
