package org.jack.backend.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceContext {
    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String currentId() {
        String value = MDC.get(MDC_KEY);
        return isGeneratedId(value) ? value : UUID.randomUUID().toString();
    }

    public static boolean isGeneratedId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
