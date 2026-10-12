package com.school.party.domain.community.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.school.party.domain.community.domain.*;
import java.time.*;
import java.util.*;

public final class ApiModels {
    private ApiModels() {}
    public static OffsetDateTime seoul(Instant time) { return time == null ? null : time.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(); }
    public record Meta(OffsetDateTime evaluatedAt, String requestId) {}
    public record Envelope<T>(T data, Meta meta) {}
    public record Failure(Error error, Meta meta) {}
    public record Error(String code, String message, @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details) {}
    public record Result<T>(T data, Instant evaluatedAt) {}
    public record ParticipationView(String participationId, String postId, ParticipationStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime cancelledAt) {}
    public record PostView(String postId, PostType type, PostStatus postStatus, OffsetDateTime referenceTime, OffsetDateTime participationDeadline, int capacity, long currentMemberCount, long remainingSeats, boolean joinable) {}
    public record Viewer(boolean isAuthor, String myParticipationStatus, boolean canJoin, boolean canCancel) {}
    public record StateView(ParticipationView participation, PostView post, Viewer viewer) {}
    public record MutationView(String action, ParticipationView participation, PostView post, Viewer viewer) {}
    public record AuthorView(String displayName) {}
    public record MemberView(String participationId, String displayName) {}
    public record MembersView(String postId, PostStatus postStatus, int capacity, long currentMemberCount, AuthorView author, List<MemberView> participants) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryPost(String postId, PostStatus postStatus, boolean isRemoved, PostType type, String title, OffsetDateTime referenceTime, String message) {}
    public record HistoryItem(String participationId, ParticipationStatus participationStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime cancelledAt, HistoryPost post) {}
    public record HistoryView(List<HistoryItem> items, int page, int size, long totalElements, int totalPages) {}
}
