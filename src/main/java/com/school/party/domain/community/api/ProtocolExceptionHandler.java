package com.school.party.domain.community.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Covers unmatched routes too; user-controller business exceptions keep their existing handling. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProtocolExceptionHandler {
    private final Clock clock;
    public ProtocolExceptionHandler(Clock clock) { this.clock = clock; }
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class,
        HttpMediaTypeNotAcceptableException.class, NoResourceFoundException.class})
    ResponseEntity<ApiModels.Failure> protocol(Exception e, HttpServletRequest request) {
        ErrorResponse response = (ErrorResponse) e;
        ErrorCode code = switch (response.getStatusCode().value()) {
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.API_NOT_FOUND;
        };
        return ResponseEntity.status(code.status()).headers(response.getHeaders())
            .body(ApiResponses.failure(code, clock.instant(), Map.of(), request));
    }
}
