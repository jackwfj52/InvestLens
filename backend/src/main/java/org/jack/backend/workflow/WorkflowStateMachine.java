package org.jack.backend.workflow;

import org.jack.backend.common.exception.*;
import java.util.Set;
import static org.jack.backend.workflow.WorkflowData.*;

/** Transition topology only. Lease, CAS, output and publication checks live at the persistence boundary. */
public final class WorkflowStateMachine {
    private WorkflowStateMachine() { }
    public static boolean allows(RunStatus from, RunStatus to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case CREATED -> to == RunStatus.QUEUED;
            case QUEUED -> Set.of(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.FAILED).contains(to);
            case RUNNING -> Set.of(RunStatus.COMPLETED, RunStatus.PARTIAL, RunStatus.FAILED, RunStatus.CANCELLED).contains(to);
            default -> false;
        };
    }
    public static boolean allows(StepStatus from, StepStatus to) {
        if (from == null || to == null) return false;
        return switch (from) {
            case PENDING -> to == StepStatus.RUNNING || to == StepStatus.SKIPPED;
            case RUNNING -> Set.of(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.PENDING).contains(to);
            case FAILED -> to == StepStatus.PENDING;
            default -> false;
        };
    }
    public static void require(RunStatus from, RunStatus to) { if (!allows(from, to)) throw new BusinessException(ErrorCode.INVALID_STATE); }
    public static void require(StepStatus from, StepStatus to) { if (!allows(from, to)) throw new BusinessException(ErrorCode.INVALID_STATE); }
}
