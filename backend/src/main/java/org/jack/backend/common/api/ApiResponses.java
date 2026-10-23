package org.jack.backend.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/** Explicit factories preserve HTTP semantics; no automatic status/body wrapping. */
public final class ApiResponses {
    private ApiResponses() {
    }

    public static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> created(URI location, T data) {
        return ResponseEntity.created(location).body(ApiResponse.success(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> accepted(URI location, T data) {
        return ResponseEntity.accepted().location(location).body(ApiResponse.success(data));
    }

    public static <T> ResponseEntity<ApiResponse<T>> inProgress(T data) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.inProgress(data));
    }
}
