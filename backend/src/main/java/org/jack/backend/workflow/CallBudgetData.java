package org.jack.backend.workflow;

import java.time.*;

public final class CallBudgetData {
    private CallBudgetData() { }
    public enum Kind { DATA, MODEL }
    public enum Purpose { STANDARD, SCHEMA_REPAIR, RETRY }
    public record Request(Kind kind, Purpose purpose, String instrumentId, LocalDate start, LocalDate end) {
        public static Request model(Purpose purpose){return new Request(Kind.MODEL,purpose,null,null,null);}
    }
    public record Window(String instrumentId, LocalDate start, LocalDate end) { }
    public record Permit(String callId, String runId, String stepId, long executionVersion, Request request,
                         LocalDateTime startedAt, LocalDateTime expiresAt, long timeoutMillis, RunDtos.RemainingBudget remaining) { }
}
