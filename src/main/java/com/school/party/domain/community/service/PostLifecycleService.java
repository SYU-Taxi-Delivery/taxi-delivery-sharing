package com.school.party.domain.community.service;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.repository.UserRepository;


import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import static com.school.party.domain.community.api.ErrorCode.*;

/** Integration hooks for future post/admin controllers. All mutations follow the same POST-first lock order. */
@Service
public class PostLifecycleService {
    private final PostRepository posts;
    private final ParticipationRepository participations;
    private final UserRepository users;
    private final Clock clock;
    public PostLifecycleService(PostRepository posts, ParticipationRepository participations, UserRepository users, Clock clock) {
        this.posts = posts; this.participations = participations; this.users = users; this.clock = clock;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void close(long postId, long actorId) {
        Post post = lock(postId); Instant now = clock.instant(); owner(post, actorId, now); active(post, now);
        if (post.getStatus() == PostStatus.CLOSED) throw new ApiException(POST_CLOSED, now);
        post.transitionTo(PostStatus.CLOSED, now);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelRecruitment(long postId, long actorId) {
        Post post = lock(postId); Instant now = clock.instant(); owner(post, actorId, now); active(post, now);
        cancelMembers(postId, now); post.transitionTo(PostStatus.CANCELLED, now);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(long postId, long actorId) {
        User actor = users.findById(actorId).orElseThrow(() -> new ApiException(AUTHENTICATION_REQUIRED, clock.instant()));
        if (actor.getRole() != Role.ADMIN) throw new ApiException(FORBIDDEN_OPERATION, clock.instant());
        Post post = lock(postId); Instant now = clock.instant();
        PostStatus status = post.effectiveStatus(now);
        if (status == PostStatus.REMOVED) throw new ApiException(POST_NOT_FOUND, now);
        if (status == PostStatus.OPEN || status == PostStatus.FULL || status == PostStatus.CLOSED) cancelMembers(postId, now);
        post.remove(now, actor);
    }
    private void cancelMembers(long postId, Instant now) {
        for (Participation p : participations.findMembers(postId, ParticipationStatus.JOINED)) p.cancel(now);
    }
    private Post lock(long id) { return posts.findByIdForUpdate(id).orElseThrow(() -> new ApiException(POST_NOT_FOUND, clock.instant())); }
    private void owner(Post p, long id, Instant now) { if (p.getAuthor().getId() != id) throw new ApiException(FORBIDDEN_OPERATION, now); }
    private void active(Post p, Instant now) {
        switch (p.effectiveStatus(now)) {
            case REMOVED -> throw new ApiException(POST_NOT_FOUND, now);
            case CANCELLED -> throw new ApiException(POST_CANCELLED, now);
            case FINISHED -> throw new ApiException(POST_FINISHED, now);
            default -> { }
        }
    }
}
