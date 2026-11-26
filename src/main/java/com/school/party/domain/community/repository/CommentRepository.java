package com.school.party.domain.community.repository;
import com.school.party.domain.community.domain.Comment;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface CommentRepository extends JpaRepository<Comment, Long> {
    /** Visible comments of a post, oldest first, with their authors in the same query. */
    @Query("select c from Comment c join fetch c.author where c.post.id = :postId and c.deletedAt is null order by c.createdAt, c.id")
    List<Comment> findVisible(@Param("postId") long postId);
}
