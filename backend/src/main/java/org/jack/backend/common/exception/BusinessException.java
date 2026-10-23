package org.jack.backend.common.exception;

import org.jack.backend.common.api.ApiError;

import java.util.List;
import java.util.Objects;

/** Keeps WealthFlow's typed exception pattern, without arbitrary public data/messages. */
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final List<ApiError> errors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, List.of(), null);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, List.of(), cause);
    }

    public BusinessException(ErrorCode errorCode, List<ApiError> errors) {
        this(errorCode, errors, null);
    }

    private BusinessException(ErrorCode errorCode, List<ApiError> errors, Throwable cause) {
        super(Objects.requireNonNull(errorCode).getMessage(), cause);
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<ApiError> getErrors() {
        return errors;
    }
}
