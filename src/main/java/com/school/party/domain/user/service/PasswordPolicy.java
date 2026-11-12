package com.school.party.domain.user.service;

import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import java.nio.charset.StandardCharsets;

/** BCrypt only uses the first 72 bytes, so longer (e.g. multi-byte) passwords are rejected, not truncated. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    private static final int MAX_BCRYPT_BYTES = 72;
    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
            || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new AuthException(AuthErrorCode.INVALID_PASSWORD);
        }
    }
}
