package org.jack.backend.infrastructure.web;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTests {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void serviceInputValidationIs400WithSafeMessages() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(new Input(null));
            var result = handler.constraint(new ConstraintViolationException(violations));
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            assertThat(result.getBody().code()).isEqualTo("VALIDATION_ERROR");
            assertThat(result.getBody().errors().get(0).field()).isEqualTo("value");
            assertThat(result.getBody().errors().get(0).message()).doesNotContain("sensitive-validator-message");
        }
    }

    @Test
    void serviceReturnValidationIsServerFailureNotUserInputError() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var service = new InvalidService();
            var violations = factory.getValidator().forExecutables().validateReturnValue(
                    service, InvalidService.class.getMethod("result"), null);
            var result = handler.constraint(new ConstraintViolationException(violations));
            assertThat(result.getStatusCode().value()).isEqualTo(500);
            assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(result.getBody().errors()).isEmpty();
        }
    }

    record Input(@NotNull(message = "sensitive-validator-message") String value) {
    }

    public static class InvalidService {
        @NotNull(message = "sensitive-validator-message")
        public String result() {
            return null;
        }
    }
}
