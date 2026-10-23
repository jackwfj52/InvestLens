package org.jack.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.common.trace.TraceContext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

/** Adapted from WealthFlow's record envelope to the InvestLens G01 contract. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(
        String schemaVersion,
        String code,
        String message,
        T data,
        List<ApiError> errors,
        String traceId,
        String timestamp
) {
    public static final String SCHEMA_VERSION = "1.0.0";
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    public ApiResponse {
        errors = List.copyOf(errors);
    }

    public static <T> ApiResponse<T> success(T data) {
        return response("OK", "请求成功", data, List.of());
    }

    public static <T> ApiResponse<T> inProgress(T data) {
        return response("RUN_IN_PROGRESS", "研究任务正在执行", data, List.of());
    }

    public static ApiResponse<Void> failure(ErrorCode code, List<ApiError> errors) {
        return response(code.name(), code.getMessage(), null, errors);
    }

    private static <T> ApiResponse<T> response(String code, String message, T data, List<ApiError> errors) {
        return new ApiResponse<>(SCHEMA_VERSION, code, message, data, errors,
                TraceContext.currentId(), UTC_MILLIS.format(Instant.now()));
    }
}
