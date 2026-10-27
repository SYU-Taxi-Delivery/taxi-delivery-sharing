package com.school.party.domain.community.service;
import com.school.party.domain.user.entity.User;

import com.school.party.domain.user.repository.UserRepository;


import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.api.ErrorCode.*;

@Service
public class ParticipationService {
    private static final Logger log = LoggerFactory.getLogger(ParticipationService.class);
    private final PostRepository posts;
    private final ParticipationRepository participations;
    private final UserRepository users;
    private final Clock clock;
    public ParticipationService(PostRepository posts, ParticipationRepository participations, UserRepository users, Clock clock) {
        this.posts = posts; this.participations = participations; this.users = users; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<MutationView> join(long postId, long actorId) {
        User actor = actor(actorId);
        Post post = locked(postId);
        Instant now = clock.instant(); // Crucially sampled after acquiring the parent lock.
        visible(post, now);
        requireOpen(post, now);
        if (!now.isBefore(post.deadline())) throw timeError(JOIN_TIME_CLOSED, post, now);
        if (post.getAuthor().getId() == actorId) throw new ApiException(SELF_PARTICIPATION_NOT_ALLOWED, now);
        Participation existing = participations.findCurrentForMutation(postId, actorId).orElse(null);
        if (existing != null && existing.getStatus() == ParticipationStatus.JOINED) throw new ApiException(ALREADY_JOINED, now);
        long count = currentCount(postId);
        if (count >= post.getCapacity()) throw new ApiException(POST_FULL, now);
        boolean initial = existing == null;
        Participation p = initial ? new Participation(post, actor, now) : existing;
        if (!initial) p.rejoin(now);
        long after = count + 1;
        if (after == post.getCapacity()) post.transitionTo(PostStatus.FULL, now);
        participations.saveAndFlush(p);
        StateView view = state(post, p, actorId, after, now);
        log.info("participation_join postId={} actorId={} action={} count={}", postId, actorId, initial ? "INITIAL_JOIN" : "REJOIN", after);
        return new Result<>(new MutationView(initial ? "INITIAL_JOIN" : "REJOIN", view.participation(), view.post(), view.viewer()), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result<MutationView> cancel(long postId, long actorId) {
        actor(actorId);
        Post post = locked(postId);
        Instant now = clock.instant();
        visible(post, now);
        requireActive(post, now);
        if (!now.isBefore(post.deadline())) throw timeError(CANCEL_TIME_CLOSED, post, now);
        if (post.getAuthor().getId() == actorId) throw new ApiException(AUTHOR_CANNOT_CANCEL_PARTICIPATION, now);
        Participation p = participations.findCurrentForMutation(postId, actorId).orElseThrow(() -> new ApiException(PARTICIPATION_NOT_FOUND, now));
        if (p.getStatus() != ParticipationStatus.JOINED) throw new ApiException(ALREADY_CANCELLED, now);
        long count = currentCount(postId);
        p.cancel(now);
        long after = count - 1;
        if (post.getStatus() == PostStatus.FULL && after < post.getCapacity()) post.transitionTo(PostStatus.OPEN, now);
        participations.flush();
        StateView view = state(post, p, actorId, after, now);
        log.info("participation_cancel postId={} actorId={} count={}", postId, actorId, after);
        return new Result<>(new MutationView("CANCEL", view.participation(), view.post(), view.viewer()), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<StateView> mine(long postId, long actorId) {
        actor(actorId);
        Post post = found(postId);
        Instant now = clock.instant(); visible(post, now);
        Participation mine = participations.findByPost_IdAndUser_Id(postId, actorId).orElse(null);
        return new Result<>(state(post, mine, actorId, currentCount(postId), now), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<MembersView> members(long postId, long actorId) {
        actor(actorId);
        Post post = found(postId);
        Instant now = clock.instant(); visible(post, now);
        // Membership, names and parent status come from one transaction snapshot.
        List<Participation> joined = participations.findMembers(postId, ParticipationStatus.JOINED);
        boolean permitted = post.getAuthor().getId() == actorId || joined.stream().anyMatch(p -> p.getUser().getId() == actorId);
        if (!permitted) throw new ApiException(PARTICIPANT_LIST_FORBIDDEN, now);
        List<MemberView> list = joined.stream().map(p -> new MemberView(p.getId().toString(), p.getUser().getName())).toList();
        return new Result<>(new MembersView(post.getId().toString(), post.effectiveStatus(now), post.getCapacity(), 1L + joined.size(), new AuthorView(post.getAuthor().getName()), list), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result<HistoryView> history(long actorId, int page, int size, String status) {
        actor(actorId);
        Instant now = clock.instant();
        if (page < 0 || size < 1 || size > 50 || ((long) page * size) > Integer.MAX_VALUE) throw new ApiException(INVALID_QUERY_PARAMETER, now);
        ParticipationStatus filter = null;
        if (status != null) {
            try { filter = ParticipationStatus.valueOf(status); }
            catch (IllegalArgumentException e) { throw new ApiException(INVALID_QUERY_PARAMETER, now); }
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<Participation> result = filter == null ? participations.findByUser_Id(actorId, pageable) : participations.findByUser_IdAndStatus(actorId, filter, pageable);
        List<HistoryItem> items = result.getContent().stream().map(p -> historyItem(p, now)).toList();
        return new Result<>(new HistoryView(items, page, size, result.getTotalElements(), result.getTotalPages()), now);
    }

    private HistoryItem historyItem(Participation p, Instant now) {
        Post post = p.getPost(); PostStatus status = post.effectiveStatus(now);
        HistoryPost dto = status == PostStatus.REMOVED
            ? new HistoryPost(post.getId().toString(), status, true, null, null, null, "관리자에 의해 삭제된 모집입니다.")
            : new HistoryPost(post.getId().toString(), status, false, post.getType(), post.getTitle(), seoul(post.getReferenceTime()), null);
        return new HistoryItem(p.getId().toString(), p.getStatus(), seoul(p.getCreatedAt()), seoul(p.getUpdatedAt()), seoul(p.getCancelledAt()), dto);
    }
    private StateView state(Post post, Participation mine, long actorId, long count, Instant now) {
        PostStatus status = post.effectiveStatus(now);
        boolean before = now.isBefore(post.deadline());
        boolean joinable = status == PostStatus.OPEN && before && count < post.getCapacity();
        boolean author = post.getAuthor().getId() == actorId;
        String myStatus = mine == null ? "NONE" : mine.getStatus().name();
        boolean joined = mine != null && mine.getStatus() == ParticipationStatus.JOINED;
        boolean active = status == PostStatus.OPEN || status == PostStatus.FULL || status == PostStatus.CLOSED;
        ParticipationView p = mine == null ? null : new ParticipationView(mine.getId().toString(), post.getId().toString(), mine.getStatus(), seoul(mine.getCreatedAt()), seoul(mine.getUpdatedAt()), seoul(mine.getCancelledAt()));
        PostView summary = new PostView(post.getId().toString(), post.getType(), status, seoul(post.getReferenceTime()), seoul(post.deadline()), post.getCapacity(), count, Math.max(0, post.getCapacity() - count), joinable);
        return new StateView(p, summary, new Viewer(author, myStatus, joinable && !author && !joined, joined && active && before));
    }
    private long currentCount(long postId) { return 1 + participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED); }
    private User actor(long id) { return users.findById(id).orElseThrow(() -> new ApiException(AUTHENTICATION_REQUIRED, clock.instant())); }
    private Post locked(long id) { return posts.findByIdForUpdate(id).orElseThrow(() -> new ApiException(POST_NOT_FOUND, clock.instant())); }
    private Post found(long id) { return posts.findById(id).orElseThrow(() -> new ApiException(POST_NOT_FOUND, clock.instant())); }
    private void visible(Post p, Instant at) { if (p.effectiveStatus(at) == PostStatus.REMOVED) throw new ApiException(POST_NOT_FOUND, at); }
    private void requireActive(Post p, Instant at) {
        PostStatus status = p.effectiveStatus(at);
        if (status == PostStatus.CANCELLED) throw new ApiException(POST_CANCELLED, at);
        if (status == PostStatus.FINISHED) throw new ApiException(POST_FINISHED, at);
    }
    private void requireOpen(Post p, Instant at) {
        requireActive(p, at);
        if (p.getStatus() == PostStatus.FULL) throw new ApiException(POST_FULL, at);
        if (p.getStatus() == PostStatus.CLOSED) throw new ApiException(POST_CLOSED, at);
    }
    private ApiException timeError(ErrorCode code, Post p, Instant now) {
        return new ApiException(code, now, Map.of("postId", p.getId().toString(), "referenceTime", seoul(p.getReferenceTime()), "participationDeadline", seoul(p.deadline())));
    }
}
