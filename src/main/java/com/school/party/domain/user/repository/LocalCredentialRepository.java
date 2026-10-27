package com.school.party.domain.user.repository;

import com.school.party.domain.user.entity.LocalCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface LocalCredentialRepository extends JpaRepository<LocalCredential, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LocalCredential c where c.userId = :userId")
    Optional<LocalCredential> findByUserIdForUpdate(@Param("userId") Long userId);
    Optional<LocalCredential> findByUserId(Long userId);
}
