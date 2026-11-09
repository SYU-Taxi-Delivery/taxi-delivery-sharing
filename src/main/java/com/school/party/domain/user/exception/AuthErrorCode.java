package com.school.party.domain.user.exception;

import org.springframework.http.HttpStatus;
import static org.springframework.http.HttpStatus.*;

/** Error codes returned by the sign-up, e-mail verification and password reset APIs. */
public enum AuthErrorCode {
    INVALID_REQUEST(BAD_REQUEST, "요청 Body가 올바르지 않습니다."),
    INVALID_SCHOOL_EMAIL(BAD_REQUEST, "학교 공식 이메일만 사용할 수 있습니다."),
    INVALID_PASSWORD(BAD_REQUEST, "비밀번호는 8자 이상 64자 이하이며 72바이트를 넘을 수 없습니다."),
    VERIFICATION_CODE_MISMATCH(BAD_REQUEST, "인증 번호가 일치하지 않습니다."),
    VERIFICATION_CODE_EXPIRED(BAD_REQUEST, "인증 번호가 만료되었거나 발급되지 않았습니다. 다시 요청해 주세요."),
    EMAIL_NOT_VERIFIED(FORBIDDEN, "이메일 인증이 완료되지 않았거나 인증 유효시간이 지났습니다."),
    EMAIL_ALREADY_REGISTERED(CONFLICT, "이미 가입된 학교 이메일입니다."),
    STUDENT_NO_ALREADY_REGISTERED(CONFLICT, "이미 등록된 학번입니다."),
    DUPLICATE_ACCOUNT(CONFLICT, "이미 가입된 계정입니다."),
    VERIFICATION_RESEND_TOO_SOON(TOO_MANY_REQUESTS, "인증 번호는 60초 후에 다시 요청할 수 있습니다."),
    VERIFICATION_ATTEMPTS_EXCEEDED(TOO_MANY_REQUESTS, "인증 번호 입력 횟수를 초과했습니다. 인증 번호를 다시 요청해 주세요."),
    MAIL_DELIVERY_FAILED(SERVICE_UNAVAILABLE, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."),
    TEMPORARILY_UNAVAILABLE(SERVICE_UNAVAILABLE, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 처리 중 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
    AuthErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    public HttpStatus status() { return status; }
    public String message() { return message; }
}
