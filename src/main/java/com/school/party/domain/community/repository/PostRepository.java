package com.school.party.domain.community.repository;
import com.school.party.domain.community.domain.Post;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.util.Optional;
public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") long id);
    @EntityGraph(attributePaths = "author")
    Page<Post> findAll(Specification<Post> spec, Pageable pageable);
    @Query("select count(p) from Post p where p.author.id = :authorId and p.id <> :excludedId and p.referenceTime = :referenceTime and p.referenceTime > :now and p.deletedAt is null and p.status in (com.school.party.domain.community.domain.PostStatus.OPEN, com.school.party.domain.community.domain.PostStatus.FULL, com.school.party.domain.community.domain.PostStatus.CLOSED)")
    long countConflicts(@Param("authorId") long authorId, @Param("excludedId") long excludedId,
        @Param("referenceTime") java.time.Instant referenceTime, @Param("now") java.time.Instant now);
}
