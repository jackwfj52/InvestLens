package org.jack.backend.workflow;

import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration
public class WorkflowClockConfiguration {
    @Bean public Clock workflowClock() { return Clock.systemUTC(); }
}
