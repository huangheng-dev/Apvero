package io.apvero.platform;

import io.apvero.platform.application.ApplicationNotFoundException;
import io.apvero.platform.release.ReleaseNotFoundException;
import io.apvero.platform.governance.BudgetExceededException;
import io.apvero.platform.governance.RateLimitExceededException;
import io.apvero.platform.knowledge.KnowledgeDisabledException;
import io.apvero.platform.knowledge.KnowledgeException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class PlatformExceptionHandler {

    @ExceptionHandler(ApplicationNotFoundException.class)
    ProblemDetail applicationNotFound(ApplicationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "APVERO_APPLICATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ReleaseNotFoundException.class)
    ProblemDetail releaseNotFound(ReleaseNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "APVERO_RELEASE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "APVERO_INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(BudgetExceededException.class)
    ProblemDetail budgetExceeded(BudgetExceededException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "APVERO_BUDGET_EXCEEDED", exception.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail rateLimitExceeded(RateLimitExceededException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "APVERO_RATE_LIMIT_EXCEEDED", exception.getMessage());
    }

    @ExceptionHandler(KnowledgeDisabledException.class)
    ProblemDetail knowledgeDisabled(KnowledgeDisabledException exception) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                KnowledgeDisabledException.CODE,
                KnowledgeDisabledException.CODE);
    }

    @ExceptionHandler(KnowledgeException.class)
    ProblemDetail knowledgeProblem(KnowledgeException exception) {
        HttpStatus status = switch (exception.category()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case CONTENT_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return problem(status, exception.code(), exception.code());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
        return problem(
                HttpStatus.CONTENT_TOO_LARGE,
                "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE",
                "APVERO_KNOWLEDGE_CONTENT_TOO_LARGE");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(DataIntegrityViolationException exception) {
        return problem(HttpStatus.BAD_REQUEST, "APVERO_REFERENCE_SCOPE_INVALID",
                "A referenced resource does not exist in the active workspace or violates an immutable constraint.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST,
                "APVERO_VALIDATION_FAILED",
                "The request does not satisfy the public contract.");
        Map<String, String> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage(),
                        (left, right) -> left));
        detail.setProperty("fields", fields);
        return detail;
    }

    private ProblemDetail problem(HttpStatus status, String code, String detailMessage) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, detailMessage);
        detail.setType(URI.create("urn:apvero:problem:" + code.toLowerCase()));
        detail.setTitle(code);
        detail.setProperty("code", code);
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }
}
