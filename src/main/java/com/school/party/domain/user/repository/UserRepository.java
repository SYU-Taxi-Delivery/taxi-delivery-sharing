package com.school.party.domain.user.repository;

import com.school.party.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsBySchoolEmail(String schoolEmail);
    boolean existsByStudentNo(String studentNo);
    Optional<User> findBySchoolEmail(String schoolEmail);
    Optional<User> findByStudentNo(String studentNo);
}