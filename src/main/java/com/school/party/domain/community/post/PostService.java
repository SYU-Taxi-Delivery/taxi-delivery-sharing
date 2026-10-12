package com.school.party.domain.community.post;
import com.school.party.domain.user.entity.User;

import com.school.party.domain.user.repository.UserRepository;


import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.api.ErrorCode.*;
import static com.school.party.domain.community.post.PostResponse.*;

@Service
public class PostService {
    private final PostRepository posts;
    private final UserRepository users;
    private final ParticipationRepository participations;
    private final PostValidator validator;
    private final Clock clock;
    public PostService(PostRepository posts, UserRepository users, ParticipationRepository participations, PostValidator validator, Clock clock) {
        this.posts = posts; this.users = users; this.participations = participations; this.validator = validator; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<Detail> create(long actorId, CreatePostRequest request) {
        User author = lockedActor(actorId); // Serializes even the very first post for this author.
        Instant now = clock.instant();
        PostContent values = validator.create(request, now);
        validator.future(values.referenceTime(), now);
        noConflict(actorId, 0, values.referenceTime(), now);
        Post post = posts.saveAndFlush(Post.create(author, values, now));
        return new Result<>(PostResponse.detail(post, actorId, null, 0, now), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<Detail> update(long postId, long actorId, UpdatePostRequest request) {
        // Global order for this module: author -> POST -> participation reads.
        // Participation/lifecycle mutations acquire POST first and never lock an author row.
        lockedActor(actorId);
        Post post = posts.findByIdForUpdate(postId).orElseThrow(() -> new ApiException(POST_NOT_FOUND, clock.instant()));
        Instant now = clock.instant();
        visible(post, now);
        if (post.getAuthor().getId() != actorId) throw new ApiException(FORBIDDEN_OPERATION, now);
        editable(post, now);
        PostContent previous = post.contentValues();
        PostContent values = validator.update(request, previous, now);
        long joined = participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED);
        if ((joined > 0 || post.effectiveStatus(now) == PostStatus.CLOSED) && validator.coreChanged(previous, values))
            throw new ApiException(POST_CORE_FIELDS_LOCKED, now);
        if (!previous.referenceTime().equals(values.referenceTime())) {
            validator.future(values.referenceTime(), now);
            noConflict(actorId, postId, values.referenceTime(), now);
        }
        post.apply(values, now);
        posts.flush();
        return new Result<>(PostResponse.detail(post, actorId, null, joined, now), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<Detail> get(long postId, long actorId) {
        actor(actorId);
        Post post = posts.findById(postId).orElseThrow(() -> new ApiException(POST_NOT_FOUND, clock.instant()));
        Instant now = clock.instant(); visible(post, now);
        Participation mine = participations.findByPost_IdAndUser_Id(postId, actorId).orElse(null);
        long joined = participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED);
        return new Result<>(PostResponse.detail(post, actorId, mine, joined, now), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<PageView> list(long actorId, int page, int size, String type, String status) {
        actor(actorId); Instant now = clock.instant();
        PostListFilter filter = PostListFilter.parse(page, size, type, status, now);
        Page<Post> found = posts.findAll(filter.specification(now), PageRequest.of(page, size, Sort.by("referenceTime", "id")));
        Map<Long, Long> joined = new HashMap<>();
        if (!found.isEmpty()) {
            for (var count : participations.countJoinedByPosts(found.getContent().stream().map(Post::getId).toList()))
                joined.put(count.getPostId(), count.getJoinedCount());
        }
        List<ListItem> items = found.map(p -> PostResponse.item(p, 1 + joined.getOrDefault(p.getId(), 0L), now)).getContent();
        return new Result<>(new PageView(items, page, size, found.getTotalElements(), found.getTotalPages()), now);
    }

    private void noConflict(long authorId, long excludedId, Instant reference, Instant now) {
        if (posts.countConflicts(authorId, excludedId, reference, now) > 0) throw new ApiException(POST_TIME_CONFLICT, now);
    }
    private void visible(Post p, Instant now) { if (p.effectiveStatus(now) == PostStatus.REMOVED) throw new ApiException(POST_NOT_FOUND, now); }
    private void editable(Post p, Instant now) {
        if (p.effectiveStatus(now) == PostStatus.CANCELLED) throw new ApiException(POST_CANCELLED, now);
        if (p.effectiveStatus(now) == PostStatus.FINISHED) throw new ApiException(POST_FINISHED, now);
    }
    private User lockedActor(long actorId) { return users.findByIdForUpdate(actorId).orElseThrow(() -> new ApiException(AUTHENTICATION_REQUIRED, clock.instant())); }
    private void actor(long actorId) { if (!users.existsById(actorId)) throw new ApiException(AUTHENTICATION_REQUIRED, clock.instant()); }
}
