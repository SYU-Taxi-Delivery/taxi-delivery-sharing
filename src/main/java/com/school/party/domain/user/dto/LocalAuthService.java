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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalAuthService {

    /** 연속 실패 5회째에 잠그고, 30분 동안 올바른 비밀번호로도 로그인할 수 없다. */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional(noRollbackFor = {IllegalArgumentException.class, IllegalStateException.class})
    public User authenticateLocal(LocalLoginRequest request) {
        String identifier = request.getIdentifier().trim();
        // 학번 또는 이메일로 사용자 조회 (이메일은 대소문자 구분 없이 저장된 정규화 값으로 조회)
        User user = userRepository.findByStudentNo(identifier)
                .orElseGet(() -> userRepository.findBySchoolEmail(SchoolEmailPolicy.canonical(identifier))
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 계정입니다.")));

        // 행 잠금(SELECT ... FOR UPDATE)으로 같은 계정의 동시 로그인 시도를 직렬화해 실패 횟수 누락을 막는다.
        LocalCredential credential = localCredentialRepository.findByUserIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("자체 인증 정보가 존재하지 않습니다."));

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // 계정 잠금 확인: 잠금 중에는 비밀번호를 검사하지 않고 실패 횟수도 늘리지 않는다.
        if (credential.isLockedAt(now)) {
            throw new IllegalStateException("계정이 임시 잠금 상태입니다. 잠시 후 다시 시도해주세요.");
        }
        // 잠금 시간이 지났다면 새로 횟수를 센다. (이전에는 한 번만 틀려도 즉시 다시 잠겼다)
        credential.clearExpiredLock(now);

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), credential.getPasswordHash())) {
            credential.recordFailure(now, MAX_FAILED_ATTEMPTS, LOCK_DURATION);
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 성공 시 실패 횟수 리셋 및 마지막 로그인 시각 기록
        credential.resetFailedLoginCount();
        user.updateLastLogin(now);
        return user;
    }
}
