package org.jack.backend.workflow;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class RetryAfterTests {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC);
    @Test void acceptsSecondsAndHttpDate() {
        assertThat(UpstreamFailure.retryAfter("2",clock)).isEqualTo(2000);
        assertThat(UpstreamFailure.retryAfter("Fri, 23 Oct 2026 00:00:03 GMT",clock)).isEqualTo(3000);
        assertThat(UpstreamFailure.retryAfter("Thu, 22 Oct 2026 00:00:00 GMT",clock)).isZero();
    }
    @Test void overflowCannotBecomeImmediateRetry() {
        assertThat(UpstreamFailure.retryAfter("99999999999999999999999999",clock)).isEqualTo(Long.MAX_VALUE);
        assertThat(UpstreamFailure.retryAfter("9223372036854775807",clock)).isEqualTo(Long.MAX_VALUE);
        assertThat(UpstreamFailure.retryAfter(null,clock)).isZero();
        assertThat(UpstreamFailure.retryAfter("invalid",clock)).isZero();
    }
}
