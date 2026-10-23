package org.jack.backend.infrastructure.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/** Exception messages, rejected values, request URLs and causes never enter responses/logs. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        return failure(exception.getErrorCode(), exception.getErrorCode().getHttpStatus(),
                new HttpHeaders(), exception.getErrors(), exception);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ApiResponse<Void>> persistence(Exception exception) {
        return failure(ErrorCode.PERSISTENCE_UNAVAILABLE, List.of(), exception);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> binding(BindException exception) {
        List<ApiError> errors = exception.getAllErrors().stream()
                .map(error -> ApiError.invalid(error instanceof FieldError field ? field.getField() : null))
                .distinct().sorted(Comparator.comparing(error -> error.field() == null ? "" : error.field()))
                .toList();
        return failure(ErrorCode.VALIDATION_ERROR, errors, exception);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> methodValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return failure(ErrorCode.INTERNAL_ERROR, List.of(), exception);
        }
        List<ApiError> errors = exception.getParameterValidationResults().stream()
                .map(result -> ApiError.invalid(result.getMethodParameter().getParameterName())).toList();
        return failure(ErrorCode.VALIDATION_ERROR, errors, exception);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraint(ConstraintViolationException exception) {
        boolean returnValueFailure = exception.getConstraintViolations().stream()
                .anyMatch(violation -> StreamSupport.stream(violation.getPropertyPath().spliterator(), false)
                        .anyMatch(node -> node.getKind() == ElementKind.RETURN_VALUE));
        if (returnValueFailure) {
            return failure(ErrorCode.INTERNAL_ERROR, List.of(), exception);
        }
        List<ApiError> errors = exception.getConstraintViolations().stream()
                .map(violation -> ApiError.invalid(violation.getPropertyPath().toString()))
                .distinct().sorted(Comparator.comparing(error -> error.field() == null ? "" : error.field()))
                .toList();
        return failure(ErrorCode.VALIDATION_ERROR, errors, exception);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> unreadable(Exception exception) {
        List<ApiError> errors = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? List.of(ApiError.invalid(mismatch.getName())) : List.of();
        return failure(ErrorCode.INVALID_REQUEST, errors, exception);
    }

    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void disconnected() { }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse protocolError) {
            int status = protocolError.getStatusCode().value();
            ErrorCode code = switch (status) {
                case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
                case 409 -> ErrorCode.INVALID_STATE;
                case 422 -> ErrorCode.DATA_UNUSABLE;
                case 429 -> ErrorCode.RATE_LIMITED;
                case 502 -> ErrorCode.UPSTREAM_ERROR;
                case 504 -> ErrorCode.UPSTREAM_TIMEOUT;
                default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
            };
            HttpHeaders headers = new HttpHeaders();
            // Preserve protocol metadata (e.g. 405 Allow), never arbitrary exception headers.
            for (String name : List.of(HttpHeaders.ALLOW, HttpHeaders.ACCEPT, "Accept-Patch", HttpHeaders.RETRY_AFTER)) {
                List<String> values = protocolError.getHeaders().get(name);
                if (values != null) {
                    headers.put(name, values);
                }
            }
            List<ApiError> errors = exception instanceof MissingServletRequestParameterException missing
                    ? List.of(ApiError.required(missing.getParameterName())) : List.of();
            return failure(code, protocolError.getStatusCode(), headers, errors, exception);
        }
        return failure(ErrorCode.INTERNAL_ERROR, List.of(), exception);
    }

    private ResponseEntity<ApiResponse<Void>> failure(ErrorCode code, List<ApiError> errors, Exception exception) {
        return failure(code, code.getHttpStatus(), new HttpHeaders(), errors, exception);
    }

    private ResponseEntity<ApiResponse<Void>> failure(ErrorCode code, HttpStatusCode status,
                                                     HttpHeaders headers, List<ApiError> errors,
                                                     Exception exception) {
        if (status.is5xxServerError()) {
            LOG.error("API failure: code={}, traceId={}, exceptionType={}", code.name(),
                    TraceContext.currentId(), exception.getClass().getSimpleName());
        }
        return new ResponseEntity<>(ApiResponse.failure(code, errors), headers, status);
    }
}
