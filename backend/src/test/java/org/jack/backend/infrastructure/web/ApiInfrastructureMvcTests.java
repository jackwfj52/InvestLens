package org.jack.backend.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.api.ApiResponses;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.common.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Boot 4 MVC slice; fixture endpoints are not part of the production API. */
@WebMvcTest(controllers = ApiInfrastructureMvcTests.FixtureController.class)
@Import({ApiJsonConfiguration.class, ApiInfrastructureMvcTests.FixtureController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class ApiInfrastructureMvcTests {
    private static final String SECRET = "sk-g03-test-credential-never-public";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void successHasExactEnvelopeAndRequestTrace() throws Exception {
        MvcResult result = mvc.perform(get("/api/_fixture/ok").header(TraceContext.HEADER, SECRET))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.schemaVersion").value("1.0.0"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value("decision_demo"))
                .andExpect(jsonPath("$.errors", hasSize(0))).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.properties()).hasSize(7);
        String traceId = body.get("traceId").asString();
        assertThat(TraceContext.isGeneratedId(traceId)).isTrue();
        assertThat(result.getResponse().getHeader(TraceContext.HEADER)).isEqualTo(traceId);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
        String timestamp = body.get("timestamp").asString();
        assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(Instant.parse(timestamp)).isNotNull();
    }

    @Test
    void createdAndAcceptedPreserveLocationAndStatus() throws Exception {
        mvc.perform(post("/api/_fixture/created"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/decisions/decision_demo"))
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(post("/api/_fixture/accepted"))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/runs/run_demo"))
                .andExpect(jsonPath("$.data.runId").value("run_demo"))
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(get("/api/_fixture/in-progress"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS"));
    }

    @Test
    void responseEntityStatusIsNotRewritten() throws Exception {
        mvc.perform(get("/api/_fixture/custom-status"))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            INVALID_REQUEST,400
            VALIDATION_ERROR,400
            INVALID_WEIGHTS,400
            CURRENCY_MISMATCH,400
            INSUFFICIENT_FUNDING,400
            UNSUPPORTED_INSTRUMENT,400
            RESOURCE_NOT_FOUND,404
            VERSION_CONFLICT,409
            SNAPSHOT_MISMATCH,409
            NEEDS_INPUT,409
            INPUT_NOT_CONFIRMED,409
            IDEMPOTENCY_CONFLICT,409
            INVALID_STATE,409
            REPORT_UNAVAILABLE,409
            LICENSE_BLOCKED,422
            DATA_UNUSABLE,422
            BUDGET_EXHAUSTED,429
            RATE_LIMITED,429
            UPSTREAM_ERROR,502
            MODEL_OUTPUT_INVALID,502
            PROVIDER_NOT_CONFIGURED,503
            PERSISTENCE_UNAVAILABLE,503
            UPSTREAM_TIMEOUT,504
            INTERNAL_ERROR,500
            """)
    void businessCodesUseTheirOwnHttpStatusAndPublicMessages(ErrorCode code, int expectedStatus) throws Exception {
        mvc.perform(get("/api/_fixture/business/" + code.name()))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    void missingInputReturns409AndFieldDetails() throws Exception {
        mvc.perform(post("/api/_fixture/needs-input"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.errors[0].field").value("horizon"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void nestedBeanValidationDoesNotExposeRejectedValuesOrCustomMessages(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(post("/api/_fixture/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"positions\":[{\"weight\":\"\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[1].field").value("positions[0].weight"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue())).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET, "rejectedValue", "stackTrace");
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void malformedUnknownAndDuplicateJsonAreRejectedWithoutEcho(CapturedOutput output) throws Exception {
        for (String input : List.of("{\"name\":\"" + SECRET,
                "{\"name\":\"safe\",\"positions\":[],\"apiKey\":\"" + SECRET + "\"}",
                "{\"name\":\"safe\",\"name\":\"" + SECRET + "\",\"positions\":[]}")) {
            MvcResult result = mvc.perform(post("/api/_fixture/input").contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
        }
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void queryValidationMissingParameterAndTypeMismatchHave400Semantics(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/_fixture/parameter").param("version", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        MvcResult mismatch = mvc.perform(get("/api/_fixture/parameter").param("version", SECRET))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("version")).andReturn();
        mvc.perform(get("/api/_fixture/parameter"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(mismatch.getResponse().getContentAsString()).doesNotContain(SECRET);
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void frameworkErrorsPreserve404405415AndAllowHeader() throws Exception {
        mvc.perform(get("/api/_fixture/missing"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post("/api/_fixture/ok"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/_fixture/input").contentType(MediaType.TEXT_PLAIN).content(SECRET))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/_fixture/protocol-error"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }

    @Test
    void internalAndUpstreamFailuresAreSafeInResponseAndLogs(CapturedOutput output) throws Exception {
        for (String endpoint : List.of("unexpected", "upstream", "protocol-error")) {
            MvcResult result = mvc.perform(get("/api/_fixture/" + endpoint))
                    .andExpect(status().is5xxServerError()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                    .andReturn();
            String traceId = result.getResponse().getHeader(TraceContext.HEADER);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET, "jdbc:mysql", "Authorization", "traceback");
            assertThat(output).contains(traceId).doesNotContain(SECRET, "jdbc:mysql", "Authorization: Bearer");
        }
    }

    @Test
    void sensitiveFieldsAreRemovedRecursivelyWhileUsageAndOtherValuesRemain() throws Exception {
        MvcResult result = mvc.perform(get("/api/_fixture/sensitive"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.nested[0].ACCESS_TOKEN").doesNotExist())
                .andExpect(jsonPath("$.data.nested[0].api_secret").doesNotExist())
                .andExpect(jsonPath("$.data.nested[0].credentials").doesNotExist())
                .andExpect(jsonPath("$.data.nested[0].usage.totalTokens").value(67))
                .andExpect(jsonPath("$.data.nested[0].weight").value("0.2"))
                .andExpect(jsonPath("$.data.model").value("test-model")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void nonEnvelopeResponsesRemainUnwrapped() throws Exception {
        mvc.perform(get("/api/_fixture/plain"))
                .andExpect(status().isOk()).andExpect(content().string("plain"));
    }

    @Test
    void bootFallbackErrorsDoNotExposeDetailsEvenWhenRequested(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/error").accept(MediaType.APPLICATION_JSON)
                        .param("trace", "true").param("message", "true")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, SECRET)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/private/" + SECRET)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET)))
                .andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET, "stackTrace", "IllegalStateException");
        assertThat(output).doesNotContain(SECRET);
    }

    @Test
    void consecutiveRequestsUseDifferentTraceIds() throws Exception {
        String first = mvc.perform(get("/api/_fixture/ok")).andReturn().getResponse().getHeader(TraceContext.HEADER);
        String second = mvc.perform(get("/api/_fixture/ok")).andReturn().getResponse().getHeader(TraceContext.HEADER);
        assertThat(first).isNotEqualTo(second);
    }

    @RestController
    @RequestMapping("/api/_fixture")
    public static class FixtureController {
        @GetMapping("/ok")
        ResponseEntity<ApiResponse<Map<String, String>>> ok() {
            return ApiResponses.ok(Map.of("id", "decision_demo"));
        }

        @PostMapping("/created")
        ResponseEntity<ApiResponse<Map<String, String>>> created() {
            return ApiResponses.created(URI.create("/api/decisions/decision_demo"), Map.of("id", "decision_demo"));
        }

        @PostMapping("/accepted")
        ResponseEntity<ApiResponse<Map<String, String>>> accepted() {
            return ApiResponses.accepted(URI.create("/api/runs/run_demo"), Map.of("runId", "run_demo"));
        }

        @GetMapping("/in-progress")
        ResponseEntity<ApiResponse<Map<String, String>>> inProgress() {
            return ApiResponses.inProgress(Map.of("runId", "run_demo"));
        }

        @GetMapping("/custom-status")
        ResponseEntity<ApiResponse<String>> customStatus() {
            return ResponseEntity.accepted().header("Retry-After", "3").body(ApiResponse.success("queued"));
        }

        @GetMapping("/business/{code}")
        ApiResponse<Void> business(@PathVariable ErrorCode code) {
            throw new BusinessException(code);
        }

        @PostMapping("/needs-input")
        ApiResponse<Void> needsInput() {
            throw new BusinessException(ErrorCode.NEEDS_INPUT, List.of(ApiError.required("horizon")));
        }

        @PostMapping("/input")
        ResponseEntity<ApiResponse<Input>> input(@Valid @RequestBody Input input) {
            return ApiResponses.ok(input);
        }

        @GetMapping("/parameter")
        ResponseEntity<ApiResponse<Integer>> parameter(@RequestParam @Min(value = 1, message = SECRET) int version) {
            return ApiResponses.ok(version);
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("jdbc:mysql://host?password=" + SECRET,
                    new IllegalArgumentException("Authorization: Bearer " + SECRET));
        }

        @GetMapping("/upstream")
        ApiResponse<Void> upstream() {
            throw new BusinessException(ErrorCode.UPSTREAM_ERROR, new RuntimeException(SECRET));
        }

        @GetMapping("/protocol-error")
        ApiResponse<Void> protocolError() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, SECRET);
        }

        @GetMapping("/sensitive")
        ResponseEntity<ApiResponse<Map<String, Object>>> sensitive() {
            return ApiResponses.ok(Map.of("apiKey", SECRET, "model", "test-model", "nested", List.of(Map.of(
                    "ACCESS_TOKEN", SECRET, "api_secret", SECRET, "credentials", Map.of("password", SECRET), "weight", "0.2",
                    "usage", Map.of("totalTokens", 67)))));
        }

        @GetMapping("/plain")
        String plain() {
            return "plain";
        }
    }

    record Input(@NotBlank(message = SECRET) String name, @Valid List<Position> positions) {
    }

    record Position(@NotBlank(message = SECRET) String weight) {
    }
}
