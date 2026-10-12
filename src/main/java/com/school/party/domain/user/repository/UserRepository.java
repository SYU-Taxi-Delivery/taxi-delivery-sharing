package com.school.party.domain.user.repository;

import com.school.party.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") long id);
    boolean existsBySchoolEmail(String schoolEmail);
    boolean existsByStudentNo(String studentNo);
    Optional<User> findBySchoolEmail(String schoolEmail);
    Optional<User> findByStudentNo(String studentNo);
}
