package com.school.party.domain.user.service;

import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Normalizes school e-mail addresses (trim + lower case) and accepts only exact school domains.
 * Defaults: syuin.ac.kr (student webmail) and syu.ac.kr (the previous single allowed domain).
 * Override with {@code app.auth.school-email.allowed-domains}.
 */
@Component
public class SchoolEmailPolicy {
    private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9][a-z0-9._%+-]{0,63}");
    private static final int MAX_LENGTH = 100;
    private final Set<String> allowedDomains;

    public SchoolEmailPolicy(@Value("${app.auth.school-email.allowed-domains:syuin.ac.kr,syu.ac.kr}") String domains) {
        this.allowedDomains = Arrays.stream(domains.split(",")).map(d -> d.trim().toLowerCase(Locale.ROOT))
            .filter(d -> !d.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the canonical address or rejects it with {@link AuthErrorCode#INVALID_SCHOOL_EMAIL}. */
    public String normalize(String raw) {
        if (raw == null) throw new AuthException(AuthErrorCode.INVALID_SCHOOL_EMAIL);
        String email = raw.trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (email.length() > MAX_LENGTH || at <= 0 || at != email.lastIndexOf('@')
            || !LOCAL_PART.matcher(email.substring(0, at)).matches() || !allowedDomains.contains(email.substring(at + 1))) {
            throw new AuthException(AuthErrorCode.INVALID_SCHOOL_EMAIL);
        }
        return email;
    }

    /** Lenient lookup form for login identifiers; does not reject non-school input. */
    public static String canonical(String raw) { return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT); }
}
