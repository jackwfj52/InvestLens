package org.jack.backend.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jack.backend.common.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object stored = request.getAttribute(ATTRIBUTE);
        String traceId = stored instanceof String value && TraceContext.isGeneratedId(value)
                ? value : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        String previous = MDC.get(TraceContext.MDC_KEY);
        MDC.put(TraceContext.MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(TraceContext.MDC_KEY);
            } else {
                MDC.put(TraceContext.MDC_KEY, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
