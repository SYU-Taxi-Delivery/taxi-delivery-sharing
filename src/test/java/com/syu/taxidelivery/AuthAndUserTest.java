package com.syu.taxidelivery;

import com.school.party.domain.user.dto.SchoolRegisterRequest;
import com.school.party.domain.user.dto.SendMailCodeRequest;
import com.school.party.domain.user.dto.VerifyMailCodeRequest;
import com.school.party.domain.user.exception.AuthErrorCode;
import com.school.party.domain.user.exception.AuthException;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserAuthRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.service.EmailVerificationStore;
import com.school.party.domain.user.service.SchoolEmailAuthService;
import com.school.party.domain.user.service.SchoolEmailPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthAndUserTest {

    private SchoolEmailAuthService schoolEmailAuthService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LocalCredentialRepository localCredentialRepository;

    @Mock
    private UserAuthRepository userAuthRepository;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);
        schoolEmailAuthService = new SchoolEmailAuthService(userRepository, userAuthRepository, localCredentialRepository,
                mailSender, passwordEncoder, new SchoolEmailPolicy("syuin.ac.kr,syu.ac.kr"), new EmailVerificationStore(clock));
    }

    private static void rejects(AuthErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("TC-AUTH-01 & 02: 이메일 인증 번호 검증 테스트")
    void verifySchoolEmailTest() {
        SendMailCodeRequest sendReq = new SendMailCodeRequest();
        ReflectionTestUtils.setField(sendReq, "schoolEmail", "20230001@syuin.ac.kr");
        schoolEmailAuthService.sendVerificationCode(sendReq);
        verify(mailSender).send(any(SimpleMailMessage.class));

        VerifyMailCodeRequest wrongVerifyReq = new VerifyMailCodeRequest();
        ReflectionTestUtils.setField(wrongVerifyReq, "schoolEmail", "20230001@syuin.ac.kr");
        ReflectionTestUtils.setField(wrongVerifyReq, "code", "000000");

        // 발송된 코드가 우연히 000000일 확률(100만분의 1)은 무시할 수 있다.
        rejects(AuthErrorCode.VERIFICATION_CODE_MISMATCH, () -> schoolEmailAuthService.verifyCode(wrongVerifyReq));
    }

    @Test
    @DisplayName("TC-AUTH-03: 허용되지 않은 이메일 도메인 입력 예외")
    void invalidEmailDomainTest() {
        SendMailCodeRequest request = new SendMailCodeRequest();
        ReflectionTestUtils.setField(request, "schoolEmail", "test@gmail.com");

        rejects(AuthErrorCode.INVALID_SCHOOL_EMAIL, () -> schoolEmailAuthService.sendVerificationCode(request));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("TC-USER-01 & 02: 미인증 이메일 가입 차단 테스트")
    void registerWithUnverifiedEmailTest() {
        SchoolRegisterRequest regReq = new SchoolRegisterRequest();
        ReflectionTestUtils.setField(regReq, "studentNo", "20230001");
        ReflectionTestUtils.setField(regReq, "name", "홍길동");
        ReflectionTestUtils.setField(regReq, "schoolEmail", "20230001@syuin.ac.kr");
        ReflectionTestUtils.setField(regReq, "password", "password123!");

        rejects(AuthErrorCode.EMAIL_NOT_VERIFIED, () -> schoolEmailAuthService.registerWithSchoolEmail(regReq));
        verifyNoInteractions(userAuthRepository, localCredentialRepository);
    }
}
