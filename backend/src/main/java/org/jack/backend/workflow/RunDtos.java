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
                         @NotNull @Positive Integer portfolioVersion, @NotNull DataMode dataMode,
                         @Pattern(regexp=ID) @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String scenarioId) {
        public Create(String schemaVersion,Integer decisionVersion,String portfolioSnapshotId,Integer portfolioVersion,DataMode dataMode) {
            this(schemaVersion,decisionVersion,portfolioSnapshotId,portfolioVersion,dataMode,null);
        }
    }
    public record Budget(String deadlineAt, int maxInstruments, int maxWindowsPerInstrument, int maxDataAttempts,
                         int maxModelAttempts, int maxSchemaRepairs, int maxRetries,
                         int dataAttempts, int modelAttempts, int schemaRepairs, int retries) { }
    public record RemainingBudget(String deadlineAt, long remainingMillis, int remainingDataAttempts,
                                  int remainingModelAttempts, int remainingStandardModelAttempts,
                                  int remainingSchemaRepairs, int remainingRetries) { }
    public record View(String schemaVersion, String runId, String decisionId, int decisionVersion,
                       String portfolioSnapshotId, int portfolioVersion, RunStatus status, Stage stage,
                       DataMode dataMode, DataQuality dataQuality, String researchSnapshotId, String snapshotHash,
                       String reportId, Budget budget, String errorCode, String createdAt, String startedAt, String finishedAt) { }
    public static View view(Run r) {
        return new View("1.0.0", r.runId(), r.decisionId(), r.decisionVersion(), r.portfolioSnapshotId(), r.portfolioVersion(),
                r.status(), r.stage(), r.dataMode(), r.dataQuality(), r.researchSnapshotId(), r.snapshotHash(), r.reportId(),
                new Budget(time(r.deadlineAt()),3,1,8,5,1,2,r.dataAttempts(),r.modelAttempts(),r.schemaRepairs(),r.retryCount()),
                r.errorCode(), time(r.createdAt()), time(r.startedAt()), time(r.finishedAt()));
    }
    public static RemainingBudget remaining(Run r,Clock clock) {
        long millis=r.status().terminal()?0:Math.max(0,Duration.between(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),r.deadlineAt()).toMillis());
        return new RemainingBudget(time(r.deadlineAt()),millis,8-r.dataAttempts(),5-r.modelAttempts(),4-r.modelAttempts()+r.schemaRepairs(),1-r.schemaRepairs(),2-r.retryCount());
    }
    static String time(LocalDateTime t) { return t == null ? null : t.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")); }
}
