package org.jack.backend.workflow;

import jakarta.validation.constraints.*;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static org.jack.backend.common.validation.ContractPatterns.ID;
import static org.jack.backend.workflow.WorkflowData.*;

public final class RunDtos {
    private RunDtos() { }
    public record Create(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                         @NotNull @Positive Integer decisionVersion,
                         @NotBlank @Pattern(regexp = ID) String portfolioSnapshotId,
                         @NotNull @Positive Integer portfolioVersion, @NotNull DataMode dataMode) { }
    public record Budget(String deadlineAt, long remainingMillis, int dataAttempts, int remainingDataAttempts,
                         int modelAttempts, int remainingModelAttempts, int schemaRepairs, int remainingSchemaRepairs,
                         int retryCount, int remainingRetries) { }
    public record View(String schemaVersion, String runId, String decisionId, int decisionVersion,
                       String portfolioSnapshotId, int portfolioVersion, RunStatus status, Stage stage,
                       DataMode dataMode, DataQuality dataQuality, String researchSnapshotId, String snapshotHash,
                       String reportId, Budget budget, String errorCode, String createdAt, String startedAt, String finishedAt) { }
    public static View view(Run r) {
        long remaining = r.status().terminal() ? 0 : Math.max(0, Duration.between(LocalDateTime.now(ZoneOffset.UTC), r.deadlineAt()).toMillis());
        return new View("1.0.0", r.runId(), r.decisionId(), r.decisionVersion(), r.portfolioSnapshotId(), r.portfolioVersion(),
                r.status(), r.stage(), r.dataMode(), r.dataQuality(), r.researchSnapshotId(), r.snapshotHash(), r.reportId(),
                new Budget(time(r.deadlineAt()), remaining, r.dataAttempts(), 8-r.dataAttempts(), r.modelAttempts(), 5-r.modelAttempts(),
                        r.schemaRepairs(), 1-r.schemaRepairs(), r.retryCount(), 2-r.retryCount()), r.errorCode(), time(r.createdAt()), time(r.startedAt()), time(r.finishedAt()));
    }
    static String time(LocalDateTime t) { return t == null ? null : t.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")); }
}
