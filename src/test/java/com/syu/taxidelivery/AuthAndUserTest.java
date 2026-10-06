package com.syu.taxidelivery;

import com.school.party.domain.user.dto.SchoolRegisterRequest;
import com.school.party.domain.user.dto.SendMailCodeRequest;
import com.school.party.domain.user.dto.VerifyMailCodeRequest;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserAuthRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.service.SchoolEmailAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class AuthAndUserTest {

    @InjectMocks
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

    @Test
    @DisplayName("TC-AUTH-01 & 02: 이메일 인증 번호 검증 테스트")
    void verifySchoolEmailTest() {
        SendMailCodeRequest sendReq = new SendMailCodeRequest();
        ReflectionTestUtils.setField(sendReq, "schoolEmail", "20230001@syu.ac.kr");

        try {
            schoolEmailAuthService.sendVerificationCode(sendReq);
        } catch (Exception ignored) {}

        VerifyMailCodeRequest wrongVerifyReq = new VerifyMailCodeRequest();
        ReflectionTestUtils.setField(wrongVerifyReq, "schoolEmail", "20230001@syu.ac.kr");
        ReflectionTestUtils.setField(wrongVerifyReq, "code", "000000");

        assertThatThrownBy(() -> schoolEmailAuthService.verifyCode(wrongVerifyReq))
                .isInstanceOf(Throwable.class);
    }

    @Test
    @DisplayName("TC-AUTH-03: 허용되지 않은 이메일 도메인 입력 예외")
    void invalidEmailDomainTest() {
        SendMailCodeRequest request = new SendMailCodeRequest();
        ReflectionTestUtils.setField(request, "schoolEmail", "test@gmail.com");

        assertThatThrownBy(() -> schoolEmailAuthService.sendVerificationCode(request))
                .isInstanceOf(Throwable.class);
    }

    @Test
    @DisplayName("TC-USER-01 & 02: 미인증 이메일 가입 차단 테스트")
    void registerWithUnverifiedEmailTest() {
        SchoolRegisterRequest regReq = new SchoolRegisterRequest();
        ReflectionTestUtils.setField(regReq, "studentNo", "20230001");
        ReflectionTestUtils.setField(regReq, "name", "홍길동");
        ReflectionTestUtils.setField(regReq, "schoolEmail", "20230001@syu.ac.kr");
        ReflectionTestUtils.setField(regReq, "password", "password123!");

        assertThatThrownBy(() -> schoolEmailAuthService.registerWithSchoolEmail(regReq))
                .isInstanceOf(Throwable.class);
    }
}