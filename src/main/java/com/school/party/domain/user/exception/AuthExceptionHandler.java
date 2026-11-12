package com.school.party.domain.user.exception;

import com.school.party.domain.community.api.ApiModels;
import com.school.party.domain.community.api.RequestMetadataFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures of the user/auth controllers into the same JSON error envelope as the community APIs.
 * Previously these exceptions escaped as HTTP 500 without a body.
 */
@RestControllerAdvice(basePackages = "com.school.party.domain.user")
public class AuthExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);
    private final Clock clock;
    public AuthExceptionHandler(Clock clock) { this.clock = clock; }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ApiModels.Failure> business(AuthException e, HttpServletRequest request) {
        log.info("auth_api_rejected requestId={} code={}", RequestMetadataFilter.requestId(request), e.code());
        return error(e.code(), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiModels.Failure> body(Exception e, HttpServletRequest request) {
        return error(AuthErrorCode.INVALID_REQUEST, request);
    }
    /** A concurrent request inserted the same student number or e-mail first (unique constraint). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiModels.Failure> duplicate(DataIntegrityViolationException e, HttpServletRequest request) {
        log.info("auth_api_integrity_violation requestId={}", RequestMetadataFilter.requestId(request));
        return error(AuthErrorCode.DUPLICATE_ACCOUNT, request);
    }
    @ExceptionHandler(MailException.class)
    ResponseEntity<ApiModels.Failure> mail(MailException e, HttpServletRequest request) {
        log.warn("auth_mail_failure requestId={}", RequestMetadataFilter.requestId(request), e);
        return error(AuthErrorCode.MAIL_DELIVERY_FAILED, request);
    }
    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ApiModels.Failure> transientFailure(Exception e, HttpServletRequest request) {
        log.warn("auth_api_transient_failure requestId={}", RequestMetadataFilter.requestId(request), e);
        return error(AuthErrorCode.TEMPORARILY_UNAVAILABLE, request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiModels.Failure> unexpected(Exception e, HttpServletRequest request) {
        log.error("auth_api_failure requestId={}", RequestMetadataFilter.requestId(request), e);
        return error(AuthErrorCode.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<ApiModels.Failure> error(AuthErrorCode code, HttpServletRequest request) {
        var body = new ApiModels.Failure(new ApiModels.Error(code.name(), code.message(), Map.of()),
            new ApiModels.Meta(ApiModels.seoul(clock.instant()), RequestMetadataFilter.requestId(request)));
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
