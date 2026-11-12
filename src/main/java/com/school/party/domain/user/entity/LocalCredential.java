package com.school.party.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "LOCAL_CREDENTIAL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LocalCredential {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Builder.Default
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    // @CreationTimestamp 대신 직접 설정: Hibernate 7은 @CreationTimestamp 컬럼을 UPDATE에서 제외하므로
    // 비밀번호 재설정 시각이 저장되지 않았다.
    @Column(name = "password_updated_at", nullable = false)
    private LocalDateTime passwordUpdatedAt;

    @PrePersist
    void initializePasswordUpdatedAt() {
        if (passwordUpdatedAt == null) passwordUpdatedAt = LocalDateTime.now();
    }

    public void increaseFailedLoginCount() {
        this.failedLoginCount += 1;
    }

    public void lockAccount(LocalDateTime lockTime) {
        this.lockedUntil = lockTime;
    }

    public void resetFailedLoginCount() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public boolean isLockedAt(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 잠금 시간이 끝난 계정은 실패 횟수를 0부터 다시 센다. */
    public void clearExpiredLock(LocalDateTime now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) resetFailedLoginCount();
    }

    /** 실패를 1회 기록하고, maxAttempts회째 실패이면 lockDuration 동안 잠근다. */
    public void recordFailure(LocalDateTime now, int maxAttempts, java.time.Duration lockDuration) {
        increaseFailedLoginCount();
        if (failedLoginCount >= maxAttempts) lockAccount(now.plus(lockDuration));
    }

    /** 비밀번호 재설정: 새 해시 저장, 변경 시각 갱신, 실패 횟수와 잠금 해제. */
    public void changePassword(String newPasswordHash, LocalDateTime now) {
        this.passwordHash = newPasswordHash;
        this.passwordUpdatedAt = now;
        resetFailedLoginCount();
    }
}