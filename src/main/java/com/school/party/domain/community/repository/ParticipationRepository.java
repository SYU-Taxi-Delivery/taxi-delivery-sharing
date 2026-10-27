package com.school.party.domain.community.repository;
import com.school.party.domain.community.domain.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ParticipationRepository extends JpaRepository<Participation, Long>, CurrentParticipationRepository {
    Optional<Participation> findByPost_IdAndUser_Id(long postId, long userId);
    long countByPost_IdAndStatus(long postId, ParticipationStatus status);
    @Query("select p from Participation p join fetch p.user where p.post.id = :postId and p.status = :status order by p.createdAt, p.id")
    List<Participation> findMembers(@Param("postId") long postId, @Param("status") ParticipationStatus status);
    @EntityGraph(attributePaths = "post")
    Page<Participation> findByUser_Id(long userId, Pageable pageable);
    @EntityGraph(attributePaths = "post")
    Page<Participation> findByUser_IdAndStatus(long userId, ParticipationStatus status, Pageable pageable);
    interface PostCount { Long getPostId(); long getJoinedCount(); }
    @Query("select p.post.id as postId, count(p) as joinedCount from Participation p where p.post.id in :postIds and p.status = com.school.party.domain.community.domain.ParticipationStatus.JOINED group by p.post.id")
    List<PostCount> countJoinedByPosts(@Param("postIds") Collection<Long> postIds);
}
