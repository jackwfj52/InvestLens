package org.jack.backend.workflow;

import org.jack.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.jack.backend.workflow.WorkflowData.*;

class WorkflowStateMachineTests {
    @Test void runTopologyMatchesTheFrozenContractIncludingEveryInvalidAndTerminalEdge() {
        var allowed = Set.of("CREATED>QUEUED", "QUEUED>RUNNING", "QUEUED>CANCELLED", "QUEUED>FAILED",
                "RUNNING>COMPLETED", "RUNNING>PARTIAL", "RUNNING>FAILED", "RUNNING>CANCELLED");
        for (RunStatus from : RunStatus.values()) for (RunStatus to : RunStatus.values()) {
            boolean expected = allowed.contains(from + ">" + to);
            assertThat(WorkflowStateMachine.allows(from, to)).as(from + ">" + to).isEqualTo(expected);
            if (!expected) assertThatThrownBy(() -> WorkflowStateMachine.require(from, to)).isInstanceOf(BusinessException.class);
        }
        assertThat(WorkflowStateMachine.allows((RunStatus) null, RunStatus.QUEUED)).isFalse();
    }
    @Test void stepRecoveryIsExplicitAndSucceededOrSkippedAreImmutable() {
        var allowed = Set.of("PENDING>RUNNING", "PENDING>SKIPPED", "RUNNING>SUCCEEDED", "RUNNING>FAILED", "RUNNING>PENDING", "FAILED>PENDING");
        for (StepStatus from : StepStatus.values()) for (StepStatus to : StepStatus.values()) {
            boolean expected = allowed.contains(from + ">" + to);
            assertThat(WorkflowStateMachine.allows(from, to)).as(from + ">" + to).isEqualTo(expected);
            if (!expected) assertThatThrownBy(() -> WorkflowStateMachine.require(from, to)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void bullAndBearShareDebateStageWithoutIntroducingAFakeStepCancelledStatus() {
        assertThat(StepKey.BULL.stage()).isEqualTo(Stage.DEBATE); assertThat(StepKey.BEAR.stage()).isEqualTo(Stage.DEBATE);
        assertThat(StepKey.QUANT.stage()).isEqualTo(Stage.QUANT);
        assertThat(StepStatus.values()).extracting(Enum::name).doesNotContain("CANCELLED");
    }
}
