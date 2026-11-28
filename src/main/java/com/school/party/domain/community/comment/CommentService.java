package com.school.party.domain.community.comment;

import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.List;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.api.ErrorCode.*;

/**
 * Comments follow docs/post-business-rules.md 11: anyone signed in may write on an
 * OPEN/FULL/CLOSED post before its reference time (participation is not required);
 * after cancellation or the reference time the comments stay readable and their
 * writers may still delete them. A removed post shows nothing.
 */
@Service
public class CommentService {
    public record CreateCommentRequest(String content) {}
    public record CommentView(String commentId, String authorName, String content, OffsetDateTime createdAt, boolean isMine) {}
    public record CommentList(List<CommentView> content) {}

    @PersistenceContext private EntityManager entityManager;
    private final CommentRepository comments;
    private final PostRepository posts;
    private final UserRepository users;
    private final Clock clock;
    public CommentService(CommentRepository comments, PostRepository posts, UserRepository users, Clock clock) {
        this.comments = comments; this.posts = posts; this.users = users; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<CommentList> list(long postId, long actorId) {
        Instant now = clock.instant();
        visible(posts.findById(postId).orElse(null), now);
        List<CommentView> items = comments.findVisible(postId).stream().map(c -> view(c, actorId)).toList();
        return new Result<>(new CommentList(items), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<CommentView> create(long postId, long actorId, String raw) {
        MutationTransactions.requireWritableReadCommitted(entityManager);
        String content = UserText.normalize(raw);
        if (content == null || content.isEmpty() || UserText.length(content) > Comment.MAX_LENGTH)
            throw new ApiException(INVALID_COMMENT, clock.instant());
        // Same POST row lock as participation and lifecycle changes, so a comment cannot land
        // after a cancellation that committed first.
        Post post = posts.findByIdForUpdate(postId).orElse(null);
        Instant now = clock.instant();
        PostStatus status = visible(post, now).effectiveStatus(now);
        if (status != PostStatus.OPEN && status != PostStatus.FULL && status != PostStatus.CLOSED)
            throw new ApiException(COMMENT_CLOSED, now);
        Comment saved = comments.saveAndFlush(new Comment(post, users.getReferenceById(actorId), content, now));
        return new Result<>(view(saved, actorId), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long postId, long commentId, long actorId) {
        Instant now = clock.instant();
        visible(posts.findById(postId).orElse(null), now);
        Comment comment = comments.findById(commentId)
            .filter(c -> !c.isDeleted() && c.getPost().getId() == postId)
            .orElseThrow(() -> new ApiException(COMMENT_NOT_FOUND, now));
        if (comment.getAuthor().getId() != actorId) throw new ApiException(COMMENT_FORBIDDEN, now);
        comment.delete(now);
    }

    private Post visible(Post post, Instant now) {
        if (post == null || post.effectiveStatus(now) == PostStatus.REMOVED) throw new ApiException(POST_NOT_FOUND, now);
        return post;
    }
    private static CommentView view(Comment c, long actorId) {
        return new CommentView(c.getId().toString(), c.getAuthor().getName(), c.getContent(), seoul(c.getCreatedAt()),
            c.getAuthor().getId() == actorId);
    }
}
