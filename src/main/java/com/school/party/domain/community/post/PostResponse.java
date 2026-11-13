package com.school.party.domain.community.post;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.school.party.domain.community.domain.*;
import java.time.*;
import java.util.*;
import static com.school.party.domain.community.api.ApiModels.*;

public final class PostResponse {
    private PostResponse() {}
    public record Viewer(boolean isAuthor, String myParticipationStatus, boolean canJoin, boolean canCancel,
        boolean canEdit, boolean canEditCore, boolean canViewCommunicationUrl) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String postId, PostType type, String title, String content, AuthorView author,
        PostStatus postStatus, RecruitmentStatus recruitmentStatus, OffsetDateTime referenceTime, OffsetDateTime participationDeadline,
        int capacity, long currentMemberCount, long remainingSeats, boolean joinable,
        String departure, String destination, String restaurantName, String pickupLocation, String orderGuide,
        String communicationUrl, OffsetDateTime createdAt, OffsetDateTime updatedAt, Viewer viewer) {}
    /**
     * List row. Carries the same schedule, places, author and counts as the detail
     * so a post never reads differently between screens. communicationUrl is only
     * filled for the author's own posts (GET /api/users/me/posts).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListItem(String postId, PostType type, String title, PostStatus postStatus, RecruitmentStatus recruitmentStatus,
        OffsetDateTime referenceTime, OffsetDateTime participationDeadline, int capacity,
        long currentMemberCount, long remainingSeats, boolean joinable, String departure,
        String destination, String restaurantName, String pickupLocation, AuthorView author, String communicationUrl) {}
    public record PageView(List<ListItem> items, int page, int size, long totalElements, int totalPages) {}

    public static Detail detail(Post post, long actorId, Participation mine, long joinedCount, Instant now) {
        PostContent v = post.contentValues(); PostStatus status = post.effectiveStatus(now);
        long count = 1 + joinedCount;
        boolean author = post.getAuthor().getId() == actorId;
        boolean joined = mine != null && mine.getStatus() == ParticipationStatus.JOINED;
        boolean active = active(status), before = now.isBefore(post.deadline());
        boolean joinable = joinable(post, count, now);
        boolean canEdit = author && active;
        boolean canLink = canViewLink(post, author, joined, now);
        Viewer viewer = new Viewer(author, mine == null ? "NONE" : mine.getStatus().name(),
            joinable && !author && !joined, joined && active && before, canEdit,
            canEdit && status != PostStatus.CLOSED && joinedCount == 0, canLink);
        return new Detail(post.getId().toString(), v.type(), v.title(), v.content(), new AuthorView(post.getAuthor().getName()),
            status, post.recruitmentStatus(now, count), seoul(v.referenceTime()), seoul(post.deadline()), v.capacity(), count,
            Math.max(0, v.capacity() - count), joinable, v.departure(), v.destination(), v.restaurantName(), v.pickupLocation(),
            v.orderGuide(), canLink ? v.communicationUrl() : null, seoul(post.getCreatedAt()), seoul(post.getUpdatedAt()), viewer);
    }
    public static ListItem item(Post p, long count, Instant now) { return item(p, count, now, null); }
    public static ListItem item(Post p, long count, Instant now, String communicationUrl) {
        PostContent v = p.contentValues();
        return new ListItem(p.getId().toString(), v.type(), v.title(), p.effectiveStatus(now), p.recruitmentStatus(now, count),
            seoul(v.referenceTime()), seoul(p.deadline()), v.capacity(), count, Math.max(0, v.capacity() - count),
            joinable(p, count, now), v.departure(), v.destination(), v.restaurantName(), v.pickupLocation(),
            new AuthorView(p.getAuthor().getName()), communicationUrl);
    }
    /**
     * The external link is private to the author and current members while the
     * schedule is active (open, full or author-closed). Cancelled members,
     * non-members and everyone after cancellation, removal or the reference time
     * never receive it.
     */
    public static boolean canViewLink(Post post, boolean author, boolean joined, Instant now) {
        return active(post.effectiveStatus(now)) && (author || joined);
    }
    private static boolean active(PostStatus s) { return s == PostStatus.OPEN || s == PostStatus.FULL || s == PostStatus.CLOSED; }
    private static boolean joinable(Post p, long count, Instant now) {
        return p.effectiveStatus(now) == PostStatus.OPEN && now.isBefore(p.deadline()) && count < p.getCapacity();
    }
}
