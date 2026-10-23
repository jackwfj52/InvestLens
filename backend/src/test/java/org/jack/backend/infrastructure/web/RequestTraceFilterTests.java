package org.jack.backend.infrastructure.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.jack.backend.common.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class RequestTraceFilterTests {
    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void restoresOuterMdcEvenWhenChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(TraceContext.MDC_KEY, "outer-context");
        MDC.put("other", "preserved");
        try {
            assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo(response.getHeader(TraceContext.HEADER));
                throw new ServletException("test failure");
            })).isInstanceOf(ServletException.class);
            assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo("outer-context");
            assertThat(MDC.get("other")).isEqualTo("preserved");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void asyncAndErrorDispatchKeepRequestIdentityWithoutLeakingThreadContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        filter.doFilter(request, response, (req, res) -> { });
        String original = response.getHeader(TraceContext.HEADER);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (req, res) -> assertThat(TraceContext.currentId()).isEqualTo(original));
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> assertThat(TraceContext.currentId()).isEqualTo(original));
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo(original);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
