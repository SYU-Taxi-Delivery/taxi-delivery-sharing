package com.school.party.domain.user.service;

import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Holds e-mail verification codes in memory. Every state change goes through a single
 * {@link ConcurrentHashMap} compute call, so concurrent requests cannot exceed the attempt limit
 * or reuse a code. Codes are kept per purpose: a sign-up code cannot reset a password.
 * <p>Limitation: state is per application instance and is lost on restart. Running several
 * instances requires a shared store (e.g. DB table or Redis).
 */
@Component
public class EmailVerificationStore {
    public enum Purpose { SIGN_UP, PASSWORD_RESET }

    public static final Duration CODE_TTL = Duration.ofMinutes(5);
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    public static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
    public static final int MAX_ATTEMPTS = 5;

    private record Key(Purpose purpose, String email) { }
    private record Pending(String code, Instant issuedAt, int failures) { }
    private enum Outcome { MATCHED, MISMATCH, EXCEEDED, EXPIRED }

    private final SecureRandom random = new SecureRandom();
    private final Map<Key, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Key, Instant> verifiedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public EmailVerificationStore(Clock clock) { this.clock = clock; }

    /** Issues a new code, replacing the previous one. Rejects a resend within {@link #RESEND_INTERVAL}. */
    public String issue(Purpose purpose, String email) {
        Instant now = clock.instant();
        purgeExpired(now);
        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.compute(new Key(purpose, email), (key, old) -> {
            if (old != null && now.isBefore(old.issuedAt().plus(RESEND_INTERVAL)))
                throw new AuthException(AuthErrorCode.VERIFICATION_RESEND_TOO_SOON);
            return new Pending(code, now, 0);
        });
        return code;
    }

    /** Withdraws a code that could not be delivered, so the user can request again immediately. */
    public void discard(Purpose purpose, String email, String code) {
        pending.computeIfPresent(new Key(purpose, email), (key, old) -> old.code().equals(code) ? null : old);
    }

    /** Consumes the code on success; counts a failure otherwise and invalidates it at the limit. */
    public void verify(Purpose purpose, String email, String input) {
        Instant now = clock.instant();
        Outcome[] outcome = {Outcome.EXPIRED};
        pending.compute(new Key(purpose, email), (key, old) -> {
            if (old == null || !now.isBefore(old.issuedAt().plus(CODE_TTL))) { outcome[0] = Outcome.EXPIRED; return null; }
            if (input != null && MessageDigest.isEqual(old.code().getBytes(StandardCharsets.US_ASCII),
                    input.getBytes(StandardCharsets.US_ASCII))) { outcome[0] = Outcome.MATCHED; return null; }
            int failures = old.failures() + 1;
            if (failures >= MAX_ATTEMPTS) { outcome[0] = Outcome.EXCEEDED; return null; }
            outcome[0] = Outcome.MISMATCH;
            return new Pending(old.code(), old.issuedAt(), failures);
        });
        switch (outcome[0]) {
            case MATCHED -> { }
            case MISMATCH -> throw new AuthException(AuthErrorCode.VERIFICATION_CODE_MISMATCH);
            case EXCEEDED -> throw new AuthException(AuthErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED);
            case EXPIRED -> throw new AuthException(AuthErrorCode.VERIFICATION_CODE_EXPIRED);
        }
    }

    public void markVerified(Purpose purpose, String email) {
        verifiedUntil.put(new Key(purpose, email), clock.instant().plus(VERIFIED_TTL));
    }

    /** Atomically takes the verified flag; only one concurrent caller can obtain it. */
    public Optional<Instant> consumeVerified(Purpose purpose, String email) {
        Instant until = verifiedUntil.remove(new Key(purpose, email));
        return until != null && clock.instant().isBefore(until) ? Optional.of(until) : Optional.empty();
    }

    /** Gives the verified flag back when the sign-up transaction did not commit. */
    public void restoreVerified(Purpose purpose, String email, Instant until) {
        verifiedUntil.putIfAbsent(new Key(purpose, email), until);
    }

    private void purgeExpired(Instant now) {
        pending.entrySet().removeIf(e -> !now.isBefore(e.getValue().issuedAt().plus(CODE_TTL)));
        verifiedUntil.entrySet().removeIf(e -> !now.isBefore(e.getValue()));
    }
}
