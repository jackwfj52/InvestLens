package org.jack.backend.decision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.jack.backend.common.api.ApiError;

import java.util.List;

/** Public input and immutable version types. Financial values stay canonical decimal strings. */
public final class DecisionDtos {
    private DecisionDtos() { }

    public static final String ID = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}";
    public static final String RATIO = "(?:0(?:\\.[0-9]{0,9}[1-9])?|1)";
    public static final String DECIMAL = "(?!-0$)-?(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,9}[1-9])?";

    public enum InputStatus { NEEDS_INPUT, READY, CONFIRMED }
    public enum AllocationMode { TARGET_WEIGHT, DELTA_PERCENTAGE_POINTS }
    public enum HorizonUnit { DAY, MONTH, YEAR }

    public record Draft(
            @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @NotBlank @Pattern(regexp = ID) String draftId,
            @NotBlank String inputText,
            @NotNull InputStatus inputStatus,
            @NotNull List<@NotNull @Valid MissingField> missingFields,
            @JsonProperty(required = true) @Valid ProposedDecision proposedDecision) { }

    public record MissingField(@NotBlank String field, @NotBlank @Pattern(regexp = ID) String code,
                               @NotBlank String message) { }

    // Field binding distinguishes omitted optional properties from explicitly invalid nulls.
    // No public mutators: once deserialized these objects are read-only to callers.
    public static final class ProposedDecision {
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Pattern(regexp = "[A-Z]{3}")
        private String baseCurrency;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Pattern(regexp = ID)
        private String portfolioSnapshotId;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Valid
        private Action action;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Valid
        private Horizon horizon;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Valid
        private Constraints constraints;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Size(min = 1)
        private List<@NotNull @Valid Hypothesis> hypotheses;

        public String baseCurrency() { return baseCurrency; }
        public String portfolioSnapshotId() { return portfolioSnapshotId; }
        public Action action() { return action; }
        public Horizon horizon() { return horizon; }
        public Constraints constraints() { return constraints; }
        public List<Hypothesis> hypotheses() { return hypotheses == null ? null : List.copyOf(hypotheses); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Action {
        @JsonProperty @NotBlank @Pattern(regexp = "REALLOCATE")
        private String type;
        @JsonProperty @NotBlank @Pattern(regexp = ID)
        private String instrumentId;
        @JsonProperty @NotBlank @Pattern(regexp = ID)
        private String fundingInstrumentId;
        @JsonProperty @NotNull
        private AllocationMode allocationMode;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Pattern(regexp = RATIO)
        private String targetWeight;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Pattern(regexp = DECIMAL)
        private String deltaPercentagePoints;

        public String type() { return type; }
        public String instrumentId() { return instrumentId; }
        public String fundingInstrumentId() { return fundingInstrumentId; }
        public AllocationMode allocationMode() { return allocationMode; }
        public String targetWeight() { return targetWeight; }
        public String deltaPercentagePoints() { return deltaPercentagePoints; }
    }

    public record Horizon(@NotNull @Positive Integer value, @NotNull HorizonUnit unit) { }

    public record Constraints(@NotBlank @Pattern(regexp = RATIO) String maxTargetWeight,
                              @NotBlank @Pattern(regexp = RATIO) String maxScenarioLoss) { }

    public record Hypothesis(
            @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @NotBlank @Pattern(regexp = ID) String hypothesisId,
            @NotBlank String statement,
            @NotBlank String mechanism,
            @NotBlank String testMethod,
            @NotBlank @Pattern(regexp = "UNVERIFIED") String supportStatus) { }

    public record Revision(
            @NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
            @NotNull @Positive Integer expectedVersion,
            @NotNull @Valid Draft draft,
            @NotNull Boolean confirm) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Version(String schemaVersion, String decisionId, int version, String inputText,
                          InputStatus inputStatus, String confirmedAt, String baseCurrency,
                          String portfolioSnapshotId, Action action, Horizon horizon,
                          Constraints constraints, List<Hypothesis> hypotheses,
                          List<ApiError> missingFields, String createdAt) {
        public Version {
            hypotheses = hypotheses == null ? null : List.copyOf(hypotheses);
            missingFields = List.copyOf(missingFields);
        }
    }
}
