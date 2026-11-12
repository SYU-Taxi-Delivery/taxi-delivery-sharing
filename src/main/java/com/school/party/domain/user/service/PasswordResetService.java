package com.school.party.domain.user.service;

import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.service.EmailVerificationStore.Purpose;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset by school e-mail code. The request step answers identically whether or not the
 * account exists, so it cannot be used to discover registered addresses. A successful reset clears
 * the failed-login counter and any lock, because the user proved ownership of the school mailbox.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private final UserRepository users;
    private final LocalCredentialRepository credentials;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final SchoolEmailPolicy emailPolicy;
    private final EmailVerificationStore verificationStore;
    private final Clock clock;

    public void sendResetCode(String rawEmail) {
        String email = emailPolicy.normalize(rawEmail);
        Optional<User> user = users.findBySchoolEmail(email);
        if (user.isEmpty() || user.get().getStatus() != UserStatus.ACTIVE
            || credentials.findByUserId(user.get().getId()).isEmpty()) return;
        String code;
        try { code = verificationStore.issue(Purpose.PASSWORD_RESET, email); }
        catch (AuthException e) {
            if (e.code() == AuthErrorCode.VERIFICATION_RESEND_TOO_SOON) return; // same answer as for unknown accounts
            throw e;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("[택시·배달 합승] 비밀번호 재설정 인증 번호 안내");
            message.setText("비밀번호 재설정을 위한 6자리 인증 번호는 [" + code + "] 입니다. "
                + EmailVerificationStore.CODE_TTL.toMinutes() + "분 안에 입력해 주세요. 요청하지 않았다면 이 메일을 무시해 주세요.");
            mailSender.send(message);
        } catch (MailException e) {
            verificationStore.discard(Purpose.PASSWORD_RESET, email, code);
            throw e;
        }
    }

    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = emailPolicy.normalize(rawEmail);
        PasswordPolicy.validate(newPassword);
        verificationStore.verify(Purpose.PASSWORD_RESET, email, code);
        User user = users.findBySchoolEmail(email)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new AuthException(AuthErrorCode.VERIFICATION_CODE_EXPIRED));
        LocalCredential credential = credentials.findByUserIdForUpdate(user.getId())
            .orElseThrow(() -> new AuthException(AuthErrorCode.VERIFICATION_CODE_EXPIRED));
        credential.changePassword(passwordEncoder.encode(newPassword), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
