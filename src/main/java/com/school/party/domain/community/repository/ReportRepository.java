package com.school.party.domain.community.repository;
import com.school.party.domain.community.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReportRepository extends JpaRepository<Report, Long> {
    boolean existsByPost_IdAndReporter_Id(long postId, long reporterId);
}
