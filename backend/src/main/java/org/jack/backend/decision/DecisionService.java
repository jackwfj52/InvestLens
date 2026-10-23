package org.jack.backend.decision;

import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.common.identity.CurrentOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.jack.backend.decision.DecisionDtos.*;

@Service
@Validated
public class DecisionService {
    private static final DateTimeFormatter UTC_MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final DecisionMapper mapper;
    private final ObjectMapper json;
    private final CurrentOwner owner;
    private final DecisionInputValidator validator;

    public DecisionService(DecisionMapper mapper, ObjectMapper json, CurrentOwner owner, DecisionInputValidator validator) {
        this.mapper = mapper;
        this.json = json;
        this.owner = owner;
        this.validator = validator;
    }

    @Transactional
    public Version create(@NotNull @Valid Draft draft) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String id = "decision_" + UUID.randomUUID();
        Version version = materialize(id, 1, draft, draft.inputStatus() == InputStatus.CONFIRMED, now);
        mapper.insertHead(id, owner.id(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        save(version, draft.draftId(), now);
        return version;
    }

    @Transactional
    public Version revise(String id, @NotNull @Valid Revision request) {
        if (request.expectedVersion() == Integer.MAX_VALUE) throw new BusinessException(ErrorCode.INVALID_STATE);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Version version = materialize(id, request.expectedVersion() + 1, request.draft(), request.confirm(), now);
        // The conditional UPDATE locks the head until this transaction commits the immutable version.
        // A competing writer resumes after commit and affects zero rows; it cannot overwrite history.
        if (mapper.advance(id, owner.id(), request.expectedVersion(), LocalDateTime.ofInstant(now, ZoneOffset.UTC)) != 1) {
            throw new BusinessException(mapper.exists(id, owner.id()) == 0
                    ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.VERSION_CONFLICT);
        }
        save(version, request.draft().draftId(), now);
        return version;
    }

    @Transactional(readOnly = true)
    public Version get(String id, Integer version) {
        String stored = mapper.find(id, owner.id(), version);
        if (stored == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return json.readValue(stored, Version.class);
    }

    /** Future Run creation must call this within its own input-freezing transaction. */
    @Transactional(readOnly = true)
    public Version requireConfirmed(String id, int version) {
        Version decision = get(id, version);
        if (decision.inputStatus() == InputStatus.NEEDS_INPUT) {
            throw new BusinessException(ErrorCode.NEEDS_INPUT, decision.missingFields());
        }
        if (decision.inputStatus() != InputStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INPUT_NOT_CONFIRMED);
        }
        return decision;
    }

    private Version materialize(String id, int number, Draft draft, boolean confirm, Instant now) {
        var missing = validator.check(draft, confirm);
        ProposedDecision proposed = draft.proposedDecision();
        InputStatus status = confirm ? InputStatus.CONFIRMED : missing.isEmpty() ? InputStatus.READY : InputStatus.NEEDS_INPUT;
        String timestamp = UTC_MILLIS.format(now);
        return new Version(ApiResponse.SCHEMA_VERSION, id, number, draft.inputText(), status,
                confirm ? timestamp : null, proposed == null ? null : proposed.baseCurrency(),
                proposed == null ? null : proposed.portfolioSnapshotId(), proposed == null ? null : proposed.action(),
                proposed == null ? null : proposed.horizon(), proposed == null ? null : proposed.constraints(),
                proposed == null ? null : proposed.hypotheses(), missing, timestamp);
    }

    private void save(Version version, String draftId, Instant now) {
        mapper.insertVersion(version.decisionId(), version.version(), draftId, json.writeValueAsString(version),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
