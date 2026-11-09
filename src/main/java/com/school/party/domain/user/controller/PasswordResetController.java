package com.school.party.domain.user.controller;

import com.school.party.domain.user.dto.PasswordResetConfirmRequest;
import com.school.party.domain.user.dto.SendMailCodeRequest;
import com.school.party.domain.user.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /** 재설정 코드 발송 API: 가입 여부와 관계없이 같은 응답을 준다. */
    @PostMapping("/send-code")
    public ResponseEntity<String> sendCode(@Valid @RequestBody SendMailCodeRequest request) {
        passwordResetService.sendResetCode(request.getSchoolEmail());
        return ResponseEntity.ok("가입된 학교 이메일이면 비밀번호 재설정 인증 번호가 발송됩니다.");
    }

    /** 인증 코드 확인 후 새 비밀번호 저장 API */
    @PostMapping("/confirm")
    public ResponseEntity<String> confirm(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.resetPassword(request.getSchoolEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.");
    }
}
