package org.jack.backend.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public field metadata only: never include rejected values or exception messages. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiError(String field, String code, String message) {

    public static ApiError invalid(String field) {
        return new ApiError(safeField(field), "INVALID_VALUE", "字段值不符合约束");
    }

    public static ApiError required(String field) {
        return new ApiError(safeField(field), "REQUIRED", "此字段为必填项");
    }

    private static String safeField(String field) {
        return field != null && field.length() <= 200
                && field.matches("[A-Za-z][A-Za-z0-9_]*(?:\\[\\d+\\]|\\.[A-Za-z][A-Za-z0-9_]*)*")
                ? field : null;
    }
}
