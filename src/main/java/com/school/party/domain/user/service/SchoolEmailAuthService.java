/**이메일 난수 코드 생성 > 코드 검증 > user, user_auth, local_credential에 동시 저장*/
package com.school.party.domain.user.service;

import com.school.party.domain.user.dto.SchoolRegisterRequest;
import com.school.party.domain.user.dto.SendMailCodeRequest;
import com.school.party.domain.user.dto.VerifyMailCodeRequest;
import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.entity.UserAuth;
import com.school.party.domain.user.enums.AuthProvider;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserAuthRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.service.EmailVerificationStore.Purpose;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class SchoolEmailAuthService {

    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final SchoolEmailPolicy emailPolicy;
    private final EmailVerificationStore verificationStore;

    /**
     * [1단계: 학교 이메일로 6자리 인증코드 발송]
     * 학교 도메인(정확히 일치)만 허용, 이미 가입된 이메일 거절, 60초 내 재요청 거절.
     * 메일 발송에 실패하면 코드를 폐기해 바로 다시 요청할 수 있다.
     */
    public void sendVerificationCode(SendMailCodeRequest request) {
        String email = emailPolicy.normalize(request.getSchoolEmail());
        if (userRepository.existsBySchoolEmail(email)) throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
        String code = verificationStore.issue(Purpose.SIGN_UP, email);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("[택시·배달 합승] 학교 학생 인증 번호 안내");
            message.setText("안녕하세요. 학생 인증을 위한 6자리 인증 번호는 [" + code + "] 입니다. "
                + EmailVerificationStore.CODE_TTL.toMinutes() + "분 안에 입력해 주세요.");
            mailSender.send(message);
        } catch (MailException e) {
            verificationStore.discard(Purpose.SIGN_UP, email, code);
            throw e;
        }
    }

    /**
     * [2단계: 사용자가 입력한 인증코드 검증]
     * 5분 유효, 5회 틀리면 코드 폐기. 성공하면 30분 동안 가입할 수 있다.
     */
    public void verifyCode(VerifyMailCodeRequest request) {
        String email = emailPolicy.normalize(request.getSchoolEmail());
        verificationStore.verify(Purpose.SIGN_UP, email, request.getCode());
        verificationStore.markVerified(Purpose.SIGN_UP, email);
    }

    /**
     * [3단계: 이메일 인증 성공 후 회원가입]
     * USERS ➔ USER_AUTH ➔ LOCAL_CREDENTIAL을 하나의 트랜잭션으로 저장한다. 어느 단계든 실패하면
     * 모두 롤백되고, 소비한 인증 상태를 되돌려 다시 시도할 수 있다. 같은 인증으로 동시에 요청하면
     * 한 요청만 인증 상태를 가져간다.
     */
    @Transactional
    public User registerWithSchoolEmail(SchoolRegisterRequest request) {
        String email = emailPolicy.normalize(request.getSchoolEmail());
        String studentNo = request.getStudentNo().trim();
        PasswordPolicy.validate(request.getPassword());

        Instant verifiedUntil = verificationStore.consumeVerified(Purpose.SIGN_UP, email)
            .orElseThrow(() -> new AuthException(AuthErrorCode.EMAIL_NOT_VERIFIED));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) verificationStore.restoreVerified(Purpose.SIGN_UP, email, verifiedUntil);
            }
        });

        if (userRepository.existsBySchoolEmail(email)) throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
        if (userRepository.existsByStudentNo(studentNo)) throw new AuthException(AuthErrorCode.STUDENT_NO_ALREADY_REGISTERED);

        User user = User.builder()
                .studentNo(studentNo)
                .name(request.getName().trim())
                .schoolEmail(email)
                .role(Role.STUDENT)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);

        // 기존 데이터와의 호환을 위해 provider 값은 기존 구현(SCHOOL_SSO)을 유지한다.
        UserAuth userAuth = UserAuth.builder()
                .user(user)
                .provider(AuthProvider.SCHOOL_SSO)
                .providerUserId(email)
                .build();
        userAuthRepository.save(userAuth);

        LocalCredential credential = LocalCredential.builder()
                .user(user)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .failedLoginCount(0)
                .build();
        localCredentialRepository.save(credential);
        // Surface unique-constraint violations from a concurrent sign-up here, inside the transaction.
        userRepository.flush();
        return user;
    }
}
