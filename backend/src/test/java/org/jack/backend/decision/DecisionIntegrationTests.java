package org.jack.backend.decision;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP + MyBatis + MySQL transactions, with only this test's UUIDs cleaned up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(DecisionIntegrationTests.FaultConfiguration.class)
class DecisionIntegrationTests {
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DecisionService service;
    @Autowired VersionInsertFault fault;
    @LocalServerPort int port;
    private final List<String> createdIds = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @AfterEach
    void cleanOwnRows() {
        for (String id : createdIds) {
            jdbc.update("DELETE FROM decision_versions WHERE decision_id = ?", id);
            jdbc.update("DELETE FROM decisions WHERE decision_id = ?", id);
        }
    }

    @Test
    void createReadEditAndOldVersionStayExact() throws Exception {
        ObjectNode draft = ready();
        draft.put("inputStatus", "CONFIRMED");
        Instant before = Instant.now().minusSeconds(1);
        Result first = create(draft);
        String id = first.data().get("decisionId").asString();
        assertThat(first.location()).isEqualTo("/api/decisions/" + id + "?version=1");
        assertThat(first.body().get("code").asString()).isEqualTo("OK");
        assertThat(first.body().get("traceId").asString()).isEqualTo(first.traceId());
        assertThat(first.data().get("confirmedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(Instant.parse(first.data().get("confirmedAt").asString())).isAfter(before).isBefore(Instant.now().plusSeconds(1));
        assertThat(first.data().get("inputText").asString()).isEqualTo(draft.get("inputText").asString());
        assertThat(first.data().get("action").get("targetWeight").isString()).isTrue();
        draft.put("inputText", "修订理由，保留原请求数值精度");
        Result updated = send("PATCH", "/api/decisions/" + id, revision(draft, 1, false));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().get("version").asInt()).isEqualTo(2);
        assertThat(updated.data().get("inputStatus").asString()).isEqualTo("READY");
        assertThat(updated.data().get("confirmedAt").isNull()).isTrue();
        assertThat(send("GET", "/api/decisions/" + id, null).data()).isEqualTo(updated.data());
        assertThat(send("GET", "/api/decisions/" + id + "?version=1", null).data()).isEqualTo(first.data());
        assertThat(service.get(id, 1).inputStatus()).isEqualTo(DecisionDtos.InputStatus.CONFIRMED);
    }

    @Test
    void missingFieldsAreDerivedAndCannotBeConfirmedOrResearched() throws Exception {
        ObjectNode draft = partial();
        Result saved = create(draft);
        String id = saved.data().get("decisionId").asString();
        assertThat(saved.data().get("inputStatus").asString()).isEqualTo("NEEDS_INPUT");
        assertThat(saved.data().get("missingFields").get(0).get("field").asString()).isEqualTo("horizon");
        assertThat(saved.data().get("missingFields").get(1).get("field").asString()).isEqualTo("constraints");
        assertThat(saved.data().toString()).doesNotContain("client-explanation-never-persist");
        assertThatThrownBy(() -> service.requireConfirmed(id, 1)).isInstanceOfSatisfying(BusinessException.class,
                error -> { assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NEEDS_INPUT);
                    assertThat(error.getErrors()).extracting("field").containsExactly("horizon", "constraints"); });
        Result invalid = send("PATCH", "/api/decisions/" + id, revision(draft, 1, true));
        assertThat(invalid.status()).isEqualTo(409);
        assertThat(invalid.body().get("code").asString()).isEqualTo("NEEDS_INPUT");
        assertThat(invalid.body().get("errors").size()).isEqualTo(2);
        assertThat(send("GET", "/api/decisions/" + id, null).data()).isEqualTo(saved.data());
    }

    @Test
    void nullProposalSavesAllSixMissingFieldsWithoutInventedValues() throws Exception {
        ObjectNode draft = partial();
        draft.putNull("proposedDecision");
        Result saved = create(draft);
        assertThat(saved.data().get("missingFields").size()).isEqualTo(6);
        for (String name : List.of("baseCurrency", "portfolioSnapshotId", "action", "horizon", "constraints", "hypotheses", "confirmedAt")) {
            assertThat(saved.data().has(name)).isTrue();
            assertThat(saved.data().get(name).isNull()).isTrue();
        }
    }

    @Test
    void explicitConfirmationCreatesVersionAndEditingRequiresConfirmationAgain() throws Exception {
        ObjectNode draft = ready();
        String id = create(draft).data().get("decisionId").asString();
        assertThatThrownBy(() -> service.requireConfirmed(id, 1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INPUT_NOT_CONFIRMED));
        Result confirmed = send("PATCH", "/api/decisions/" + id, revision(draft, 1, true));
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.data().get("inputStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.data().get("confirmedAt")).isEqualTo(confirmed.data().get("createdAt"));
        assertThat(service.requireConfirmed(id, 2).version()).isEqualTo(2);
        draft.put("inputStatus", "CONFIRMED");
        Result edited = send("PATCH", "/api/decisions/" + id, revision(draft, 2, false));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.data().get("inputStatus").asString()).isEqualTo("READY");
        assertThat(edited.data().get("confirmedAt").isNull()).isTrue();
        assertThat(service.requireConfirmed(id, 2).inputStatus()).isEqualTo(DecisionDtos.InputStatus.CONFIRMED);
    }

    @Test
    void simultaneousHttpWritersProduceOneSuccessOneConflictAndTwoVersions() throws Exception {
        Result original = create(ready());
        String id = original.data().get("decisionId").asString();
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch waiting = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var attempts = Stream.of("first writer", "second writer").map(text -> pool.submit(() -> {
                ObjectNode draft = ready();
                draft.put("inputText", text);
                waiting.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return send("PATCH", "/api/decisions/" + id, revision(draft, 1, false));
            })).toList();
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Result> results = List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS));
            assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
            Result loser = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(loser.body().get("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(loser.body().get("data").isNull()).isTrue();
            Result winner = results.stream().filter(result -> result.status() == 200).findFirst().orElseThrow();
            assertThat(send("GET", "/api/decisions/" + id, null).data()).isEqualTo(winner.data());
            assertThat(send("GET", "/api/decisions/" + id + "?version=1", null).data()).isEqualTo(original.data());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_versions WHERE decision_id = ?", Integer.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT current_version FROM decisions WHERE decision_id = ?", Integer.class, id)).isEqualTo(2);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void staleVersionIsRejectedAndIdsAreOwnerScopedAndCaseSensitive() throws Exception {
        Result original = create(ready());
        String id = original.data().get("decisionId").asString();
        Result stale = send("PATCH", "/api/decisions/" + id, revision(ready(), 2, false));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(send("GET", "/api/decisions/" + id + "?version=99", null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/decisions/" + id.toUpperCase(java.util.Locale.ROOT), null).status()).isEqualTo(404);
        for (String query : List.of("0", "-1", "wat")) {
            assertThat(send("GET", "/api/decisions/" + id + "?version=" + query, null).status()).isEqualTo(400);
        }
        jdbc.update("UPDATE decisions SET owner_id = 'other-test-owner' WHERE decision_id = ?", id);
        assertThat(send("GET", "/api/decisions/" + id, null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/decisions/" + id + "?version=1", null).status()).isEqualTo(404);
        Result hidden = send("PATCH", "/api/decisions/" + id, revision(ready(), 1, false));
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.body().get("code").asString()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT current_version FROM decisions WHERE decision_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(send("GET", "/api/decisions/not-found", null).status()).isEqualTo(404);
    }

    @Test
    void failedVersionInsertRollsBackHttpCreateAndRevisionHeadTogether() throws Exception {
        Result original = create(ready());
        String id = original.data().get("decisionId").asString();
        ObjectNode draft = ready();
        fault.failDraftId.set(draft.get("draftId").asString());
        try {
            Result failed = send("PATCH", "/api/decisions/" + id, revision(draft, 1, false));
            assertThat(failed.status()).isEqualTo(500);
            assertThat(failed.body().toString()).doesNotContain("injected-write-failure");
        } finally { fault.failDraftId.set(null); }
        assertThat(send("GET", "/api/decisions/" + id, null).data()).isEqualTo(original.data());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_versions WHERE decision_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(send("PATCH", "/api/decisions/" + id, revision(draft, 1, false)).status()).isEqualTo(200);
        ObjectNode failedCreate = ready();
        fault.failDraftId.set(failedCreate.get("draftId").asString());
        try {
            assertThat(send("POST", "/api/decisions", failedCreate).status()).isEqualTo(500);
        } finally { fault.failDraftId.set(null); }
        String rolledBack = fault.failedDecisionId.get();
        assertThat(rolledBack).isNotNull().isNotEqualTo(id);
        createdIds.add(rolledBack);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decisions WHERE decision_id = ?", Integer.class, rolledBack)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_versions WHERE decision_id = ?", Integer.class, rolledBack)).isZero();
    }

    @Test
    void positivePercentagePointsStayDistinctFromTargetWeight() throws Exception {
        ObjectNode draft = ready();
        ObjectNode action = (ObjectNode) draft.get("proposedDecision").get("action");
        action.put("allocationMode", "DELTA_PERCENTAGE_POINTS");
        action.remove("targetWeight");
        action.put("deltaPercentagePoints", "10");
        Result saved = create(draft);
        assertThat(saved.data().get("action").get("deltaPercentagePoints").asString()).isEqualTo("10");
        assertThat(saved.data().get("action").has("targetWeight")).isFalse();
        assertThat(send("GET", saved.location(), null).data()).isEqualTo(saved.data());
    }

    @ParameterizedTest(name = "reject {0}")
    @MethodSource("invalidDrafts")
    void invalidInputNeverPersists(String name, Consumer<ObjectNode> change) throws Exception {
        ObjectNode draft = ready();
        String draftId = draft.get("draftId").asString();
        change.accept(draft);
        Result result = send("POST", "/api/decisions", draft);
        assertThat(result.status()).as(name).isEqualTo(400);
        assertThat(result.body().get("data").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM decision_versions WHERE draft_id = ?", Integer.class, draftId)).isZero();
    }

    static Stream<Arguments> invalidDrafts() {
        return Stream.of(
                invalid("numeric financial value", d -> action(d).put("targetWeight", 0.3)),
                invalid("exponent", d -> action(d).put("targetWeight", "3e-1")),
                invalid("trailing zero", d -> action(d).put("targetWeight", "0.30")),
                invalid("negative zero", d -> action(d).put("targetWeight", "-0")),
                invalid("excess precision", d -> action(d).put("targetWeight", "0.12345678901")),
                invalid("zero target", d -> action(d).put("targetWeight", "0")),
                invalid("negative delta", d -> delta(d, "-10")),
                invalid("zero delta", d -> delta(d, "0")),
                invalid("delta above 100 points", d -> delta(d, "100.1")),
                invalid("both allocation modes", d -> action(d).put("deltaPercentagePoints", "10")),
                invalid("neither allocation mode", d -> action(d).remove("targetWeight")),
                invalid("same funding instrument", d -> action(d).put("fundingInstrumentId", action(d).get("instrumentId").asString())),
                invalid("unknown allocation field", d -> action(d).put("weightUnit", "PERCENT")),
                invalid("numeric enum", d -> action(d).put("allocationMode", 0)),
                invalid("float integer", d -> ((ObjectNode) proposed(d).get("horizon")).put("value", 6.5)),
                invalid("string integer", d -> ((ObjectNode) proposed(d).get("horizon")).put("value", "6")),
                invalid("zero horizon", d -> ((ObjectNode) proposed(d).get("horizon")).put("value", 0)),
                invalid("incomplete constraints", d -> ((ObjectNode) proposed(d).get("constraints")).remove("maxScenarioLoss")),
                invalid("ratio above one", d -> ((ObjectNode) proposed(d).get("constraints")).put("maxScenarioLoss", "1.1")),
                invalid("already supported hypothesis", d -> ((ObjectNode) proposed(d).get("hypotheses").get(0)).put("supportStatus", "SUPPORTED")),
                invalid("duplicate hypothesis id", d -> ((tools.jackson.databind.node.ArrayNode) proposed(d).get("hypotheses")).add(proposed(d).get("hypotheses").get(0).deepCopy())),
                invalid("empty hypotheses", d -> proposed(d).putArray("hypotheses")),
                invalid("unsupported currency", d -> proposed(d).put("baseCurrency", "CNY")),
                invalid("unknown owner", d -> d.put("ownerId", "someone-else")),
                invalid("server timestamp", d -> d.put("confirmedAt", "2026-10-23T00:00:00.000Z")),
                invalid("schema mismatch", d -> d.put("schemaVersion", "2.0.0")),
                invalid("blank original text", d -> d.put("inputText", " ")),
                invalid("missing proposal field", d -> d.remove("proposedDecision")),
                invalid("explicit null optional field", d -> proposed(d).putNull("horizon")),
                invalid("incomplete ready input", d -> proposed(d).remove("horizon")),
                invalid("empty missing fields for partial input", d -> { d.put("inputStatus", "NEEDS_INPUT"); proposed(d).remove("horizon"); }),
                invalid("inconsistent needs input", d -> { d.put("inputStatus", "NEEDS_INPUT"); d.putArray("missingFields").addObject().put("field", "horizon").put("code", "REQUIRED").put("message", "missing"); })
        );
    }

    private static Arguments invalid(String name, Consumer<ObjectNode> change) { return Arguments.of(name, change); }
    private static ObjectNode proposed(ObjectNode draft) { return (ObjectNode) draft.get("proposedDecision"); }
    private static ObjectNode action(ObjectNode draft) { return (ObjectNode) proposed(draft).get("action"); }

    private static void delta(ObjectNode draft, String value) {
        action(draft).put("allocationMode", "DELTA_PERCENTAGE_POINTS");
        action(draft).remove("targetWeight");
        action(draft).put("deltaPercentagePoints", value);
    }

    @ParameterizedTest(name = "reject revision {0}")
    @MethodSource("invalidRevisions")
    void invalidRevisionCannotAdvanceHead(String name, Consumer<ObjectNode> change) throws Exception {
        Result original = create(ready());
        String id = original.data().get("decisionId").asString();
        ObjectNode revision = revision(ready(), 1, false);
        change.accept(revision);
        assertThat(send("PATCH", "/api/decisions/" + id, revision).status()).as(name).isEqualTo(400);
        assertThat(send("GET", "/api/decisions/" + id, null).data()).isEqualTo(original.data());
    }

    static Stream<Arguments> invalidRevisions() {
        return Stream.of(
                invalid("missing expected version", r -> r.remove("expectedVersion")),
                invalid("zero expected version", r -> r.put("expectedVersion", 0)),
                invalid("string expected version", r -> r.put("expectedVersion", "1")),
                invalid("fractional expected version", r -> r.put("expectedVersion", 1.5)),
                invalid("missing confirmation", r -> r.remove("confirm")),
                invalid("string confirmation", r -> r.put("confirm", "false")),
                invalid("missing draft", r -> r.remove("draft")),
                invalid("schema mismatch", r -> r.put("schemaVersion", "0.9.0"))
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean VersionInsertFault versionInsertFault() { return new VersionInsertFault(); }
    }

    /** Fault only the selected test draft, after the real head write; no database is mocked. */
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static class VersionInsertFault implements Interceptor {
        final AtomicReference<String> failDraftId = new AtomicReference<>();
        final AtomicReference<String> failedDecisionId = new AtomicReference<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals(DecisionMapper.class.getName() + ".insertVersion")
                    && invocation.getArgs()[1] instanceof Map<?, ?> params
                    && params.get("draftId").equals(failDraftId.get())) {
                failedDecisionId.set((String) params.get("id"));
                throw new DataIntegrityViolationException("injected-write-failure");
            }
            return invocation.proceed();
        }
    }

    private ObjectNode ready() throws Exception {
        ObjectNode draft = (ObjectNode) json.readTree(getClass().getResourceAsStream("/decision-ready.json"));
        draft.put("draftId", "draft_" + UUID.randomUUID());
        return draft;
    }

    private ObjectNode partial() throws Exception {
        ObjectNode draft = ready();
        draft.put("inputStatus", "NEEDS_INPUT");
        proposed(draft).remove("horizon");
        proposed(draft).remove("constraints");
        draft.putArray("missingFields").addObject().put("field", "clientGuess")
                .put("code", "REQUIRED").put("message", "client-explanation-never-persist");
        return draft;
    }

    private ObjectNode revision(ObjectNode draft, int expectedVersion, boolean confirm) {
        ObjectNode revision = json.createObjectNode().put("schemaVersion", "1.0.0")
                .put("expectedVersion", expectedVersion).put("confirm", confirm);
        revision.set("draft", draft);
        return revision;
    }

    private Result create(ObjectNode draft) throws Exception {
        Result result = send("POST", "/api/decisions", draft);
        if (result.status() == 201) createdIds.add(result.data().get("decisionId").asString());
        assertThat(result.status()).as(result.body().toString()).isEqualTo(201);
        return result;
    }

    private Result send(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Owner-Id", "untrusted-header");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), json.readTree(response.body()),
                response.headers().firstValue("Location").orElse(null),
                response.headers().firstValue("X-Trace-Id").orElse(null));
    }

    private record Result(int status, JsonNode body, String location, String traceId) {
        JsonNode data() { return body.get("data"); }
    }
}
