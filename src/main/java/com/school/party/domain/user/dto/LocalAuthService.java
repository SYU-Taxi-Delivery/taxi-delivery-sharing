package com.school.party.domain.user.service;

import com.school.party.domain.user.dto.LocalLoginRequest;
import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.school.party.domain.user.enums.UserStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class LocalAuthService {

    /** 연속 실패 5회째에 잠그고, 30분 동안 올바른 비밀번호로도 로그인할 수 없다. */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final LocalCredentialRepository localCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /**
     * 비밀번호 비교(bcrypt)는 DB 잠금과 트랜잭션 밖에서 하고, 실패 횟수·잠금 갱신만 행 잠금 안에서 한다 (#33).
     * 계정 상태는 비밀번호 비교와 실패 횟수 갱신보다 먼저 확인한다 (#37).
     */
    public User authenticateLocal(LocalLoginRequest request) {
        String identifier = request.getIdentifier().trim();
        // 학번 또는 이메일로 사용자 조회 (이메일은 대소문자 구분 없이 저장된 정규화 값으로 조회)
        User user = userRepository.findByStudentNo(identifier)
                .orElseGet(() -> userRepository.findBySchoolEmail(SchoolEmailPolicy.canonical(identifier))
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 계정입니다.")));

        // 정지·탈퇴 계정은 비밀번호를 검사하지 않고, 실패 횟수·잠금·마지막 로그인 시각도 바꾸지 않는다.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("사용할 수 없는 계정입니다.");
        }

        LocalCredential snapshot = localCredentialRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("자체 인증 정보가 존재하지 않습니다."));
        // 이미 잠긴 계정은 bcrypt 계산 없이 바로 거절한다. (최종 판정은 아래 잠금 구간에서 다시 한다)
        if (snapshot.isLockedAt(now())) {
            throw new IllegalStateException(LOCKED_MESSAGE);
        }
        String checkedHash = snapshot.getPasswordHash();
        boolean matches = passwordEncoder.matches(request.getPassword(), checkedHash);

        Result result = new TransactionTemplate(transactionManager).execute(tx -> {
            // 행 잠금(SELECT ... FOR UPDATE)으로 같은 계정의 동시 로그인 시도를 직렬화해 실패 횟수 누락을 막는다.
            LocalCredential credential = localCredentialRepository.findByUserIdForUpdate(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("자체 인증 정보가 존재하지 않습니다."));
            LocalDateTime now = now();

            // 계정 잠금 확인: 잠금 중에는 비밀번호를 검사하지 않고 실패 횟수도 늘리지 않는다.
            if (credential.isLockedAt(now)) return new Result(Outcome.LOCKED, null);
            // 비교하는 동안 정지·탈퇴됐다면 아무것도 바꾸지 않고 거절한다.
            User current = userRepository.findById(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 계정입니다."));
            if (current.getStatus() != UserStatus.ACTIVE) return new Result(Outcome.INACTIVE, null);
            // 잠금 시간이 지났다면 새로 횟수를 센다.
            credential.clearExpiredLock(now);

            // 비교한 뒤 비밀번호가 바뀌었다면(재설정과 동시 요청) 새 해시로 다시 비교한다. 드문 경우라 잠금 안에서 한다.
            boolean ok = credential.getPasswordHash().equals(checkedHash)
                    ? matches : passwordEncoder.matches(request.getPassword(), credential.getPasswordHash());
            if (!ok) {
                credential.recordFailure(now, MAX_FAILED_ATTEMPTS, LOCK_DURATION);
                return new Result(Outcome.WRONG_PASSWORD, null);   // 실패 횟수는 커밋한 뒤 거절한다.
            }

            // 성공 시 실패 횟수 리셋 및 마지막 로그인 시각 기록
            credential.resetFailedLoginCount();
            current.updateLastLogin(now);
            return new Result(Outcome.SUCCESS, current);
        });

        switch (result.outcome()) {
            case LOCKED -> throw new IllegalStateException(LOCKED_MESSAGE);
            case WRONG_PASSWORD -> throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
            case INACTIVE -> throw new IllegalArgumentException("사용할 수 없는 계정입니다.");
            case SUCCESS -> { }
        }
        return result.user();
    }

    private enum Outcome { SUCCESS, WRONG_PASSWORD, LOCKED, INACTIVE }
    private record Result(Outcome outcome, User user) {}

    private static final String LOCKED_MESSAGE = "계정이 임시 잠금 상태입니다. 잠시 후 다시 시도해주세요.";

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
