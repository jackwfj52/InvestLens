package org.jack.backend.decision;

import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.jack.backend.decision.DecisionDtos.*;

@Component
public class DecisionInputValidator {
    public List<ApiError> check(Draft draft, boolean confirm) {
        ProposedDecision proposed = draft.proposedDecision();
        List<ApiError> missing = new ArrayList<>();
        if (proposed == null || proposed.baseCurrency() == null) missing.add(ApiError.required("baseCurrency"));
        if (proposed == null || proposed.portfolioSnapshotId() == null) missing.add(ApiError.required("portfolioSnapshotId"));
        if (proposed == null || proposed.action() == null) missing.add(ApiError.required("action"));
        if (proposed == null || proposed.horizon() == null) missing.add(ApiError.required("horizon"));
        if (proposed == null || proposed.constraints() == null) missing.add(ApiError.required("constraints"));
        if (proposed == null || proposed.hypotheses() == null) missing.add(ApiError.required("hypotheses"));
        if (proposed != null) {
            if (proposed.baseCurrency() != null && !"USD".equals(proposed.baseCurrency())) {
                throw new BusinessException(ErrorCode.CURRENCY_MISMATCH, List.of(ApiError.invalid("baseCurrency")));
            }
            if (proposed.action() != null) checkAction(proposed.action());
            List<Hypothesis> hypotheses = proposed.hypotheses();
            if (hypotheses != null) {
                var ids = new HashSet<String>();
                for (int i = 0; i < hypotheses.size(); i++) {
                    if (!ids.add(hypotheses.get(i).hypothesisId())) {
                        invalid("hypotheses[" + i + "].hypothesisId");
                    }
                }
            }
        }
        if (confirm && !missing.isEmpty()) throw new BusinessException(ErrorCode.NEEDS_INPUT, missing);
        if ((draft.inputStatus() == InputStatus.NEEDS_INPUT) != !missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    missing.isEmpty() ? List.of(ApiError.invalid("inputStatus")) : missing);
        }
        if (draft.missingFields().isEmpty() != missing.isEmpty()) invalid("missingFields");
        // Client explanations are advisory; persist only backend-generated paths and messages.
        return List.copyOf(missing);
    }

    private void checkAction(Action action) {
        if (action.instrumentId().equals(action.fundingInstrumentId())) invalid("action.fundingInstrumentId");
        if (action.allocationMode() == AllocationMode.TARGET_WEIGHT) {
            if (action.targetWeight() == null) required("action.targetWeight");
            if (action.deltaPercentagePoints() != null) invalid("action.deltaPercentagePoints");
            if (new BigDecimal(action.targetWeight()).signum() <= 0) invalid("action.targetWeight");
        } else {
            if (action.deltaPercentagePoints() == null) required("action.deltaPercentagePoints");
            if (action.targetWeight() != null) invalid("action.targetWeight");
            BigDecimal delta = new BigDecimal(action.deltaPercentagePoints());
            if (delta.signum() <= 0 || delta.compareTo(new BigDecimal("100")) > 0) {
                invalid("action.deltaPercentagePoints");
            }
        }
    }

    private static void invalid(String field) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(ApiError.invalid(field)));
    }

    private static void required(String field) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(ApiError.required(field)));
    }
}
