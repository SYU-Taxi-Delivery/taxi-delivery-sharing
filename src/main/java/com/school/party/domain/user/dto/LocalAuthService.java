package com.school.party.domain.user.service;

import com.school.party.domain.user.dto.LocalLoginRequest;
import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalAuthService {

    private final UserRepository userRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User authenticateLocal(LocalLoginRequest request) {
        // 학번 또는 이메일로 사용자 조회
        User user = userRepository.findByStudentNo(request.getIdentifier())
                .orElseGet(() -> userRepository.findBySchoolEmail(request.getIdentifier())
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 계정입니다.")));

        LocalCredential credential = localCredentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("자체 인증 정보가 존재하지 않습니다."));

        // 계정 잠금 확인
        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("계정이 임시 잠금 상태입니다. 잠시 후 다시 시도해주세요.");
        }

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), credential.getPasswordHash())) {
            credential.increaseFailedLoginCount();
            if (credential.getFailedLoginCount() >= 5) {
                credential.lockAccount(LocalDateTime.now().plusMinutes(30));
            }
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 성공 시 실패 횟수 리셋
        credential.resetFailedLoginCount();
        return user;
    }
}