package com.school.party.domain.user.exception;

/** Business rejection of an authentication request; mapped to JSON by {@link AuthExceptionHandler}. */
public class AuthException extends RuntimeException {
    private final AuthErrorCode code;
    public AuthException(AuthErrorCode code) { super(code.message()); this.code = code; }
    public AuthErrorCode code() { return code; }
}
