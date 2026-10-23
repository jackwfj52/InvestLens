package org.jack.backend.workflow;

import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import java.time.LocalDateTime;

public final class WorkflowData {
    private WorkflowData() { }
    public enum RunStatus { CREATED, QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED;
        public boolean terminal() { return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED; }
    }
    public enum Stage { PLAN, RESEARCH, QUANT, DEBATE, JUDGE, VALIDATE }
    public enum StepKey { PLAN, RESEARCH, QUANT, BULL, BEAR, JUDGE, VALIDATE;
        public Stage stage() { return switch (this) { case BULL, BEAR -> Stage.DEBATE; default -> Stage.valueOf(name()); }; }
    }
    public enum StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }
    public enum DataQuality { VALID, PARTIAL, UNUSABLE }
    public enum EventType { RUN_QUEUED, STEP_STARTED, STEP_SUCCEEDED, STEP_FAILED, RUN_TERMINATED }
    /** UTC database timestamps; a future API layer formats them using the frozen RFC3339 contract. */
    public record Run(String runId, String decisionId, int decisionVersion, String portfolioSnapshotId,
                      int portfolioVersion, RunStatus status, Stage stage, DataMode dataMode, DataQuality dataQuality,
                      String researchSnapshotId, String snapshotHash, String reportId, String errorCode,
                      LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime deadlineAt,
                      int dataAttempts, int modelAttempts, int schemaRepairs, int retryCount, long revision, long eventSequence) { }
    public record Step(String stepId, String runId, StepKey stepKey, StepStatus status, int attempt,
                       String inputHash, String outputRef, LocalDateTime leaseUntil, long executionVersion,
                       String errorCode, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record Event(String eventId, String runId, long sequence, EventType type, Stage stage,
                        RunStatus status, LocalDateTime createdAt, String message) { }
}
