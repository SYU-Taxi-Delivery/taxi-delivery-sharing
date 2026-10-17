package com.school.party.domain.community.domain;
import com.school.party.domain.user.entity.User;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Check;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.time.Duration;

@Entity @Table(name = "post")
@Check(name = "ck_post_capacity", constraints = "(type='TAXI' AND capacity BETWEEN 2 AND 4) OR (type='DELIVERY' AND capacity BETWEEN 2 AND 10)")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false) private User author;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) private PostType type;
    @Column(nullable = false, length = 60) private String title;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(nullable = false) private int capacity;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) private PostStatus status;
    @Column(nullable = false) private Instant referenceTime;
    @Column(length = 500) private String communicationUrl;
    @Column(length = 100) private String departure;
    @Column(length = 100) private String destination;
    @Column(length = 100) private String restaurantName;
    @Column(length = 100) private String pickupLocation;
    @Column(length = 500) private String orderGuide;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant deletedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "deleted_by") private User deletedBy;
    protected Post() {}
    public static Post taxi(User author, int capacity, Instant referenceTime, Instant now) {
        return create(author, PostType.TAXI, capacity, referenceTime, now);
    }
    public static Post delivery(User author, int capacity, Instant referenceTime, Instant now) {
        return create(author, PostType.DELIVERY, capacity, referenceTime, now);
    }
    private static Post create(User author, PostType type, int capacity, Instant referenceTime, Instant now) {
        if (capacity < 2 || capacity > (type == PostType.TAXI ? 4 : 10)) throw new IllegalArgumentException("Invalid capacity");
        return create(author, new PostContent(type, type == PostType.TAXI ? "석계역 → 삼육대학교" : "교내 공동 배달",
            "모집 안내", capacity, referenceTime, null, type == PostType.TAXI ? "석계역" : null,
            type == PostType.TAXI ? "삼육대학교" : null, type == PostType.DELIVERY ? "교내 음식점" : null,
            type == PostType.DELIVERY ? "정문" : null, null), now);
    }
    public static Post create(User author, PostContent values, Instant now) {
        Post p = new Post(); p.author = author; p.type = values.type(); p.apply(values, now);
        p.status = PostStatus.OPEN; p.createdAt = now;
        return p;
    }
    public PostContent contentValues() {
        return new PostContent(type, title, content, capacity, referenceTime, communicationUrl,
            departure, destination, restaurantName, pickupLocation, orderGuide);
    }
    public void apply(PostContent values, Instant now) {
        if (type != values.type()) throw new IllegalArgumentException("Post type is immutable");
        title = values.title(); content = values.content(); capacity = values.capacity(); referenceTime = values.referenceTime();
        communicationUrl = values.communicationUrl(); departure = values.departure(); destination = values.destination();
        restaurantName = values.restaurantName(); pickupLocation = values.pickupLocation(); orderGuide = values.orderGuide(); updatedAt = now;
    }
    public PostStatus effectiveStatus(Instant now) {
        if (deletedAt != null || status == PostStatus.REMOVED) return PostStatus.REMOVED;
        if (status == PostStatus.CANCELLED) return PostStatus.CANCELLED;
        if (status == PostStatus.FINISHED || !now.isBefore(referenceTime)) return PostStatus.FINISHED;
        return status;
    }
    public void transitionTo(PostStatus next, Instant now) {
        boolean active = status == PostStatus.OPEN || status == PostStatus.FULL || status == PostStatus.CLOSED;
        boolean allowed = (next == PostStatus.REMOVED && status != PostStatus.REMOVED)
            || (active && (next == PostStatus.CANCELLED || next == PostStatus.FINISHED))
            || ((status == PostStatus.OPEN || status == PostStatus.FULL) && next == PostStatus.CLOSED)
            || (status == PostStatus.OPEN && next == PostStatus.FULL)
            || (status == PostStatus.FULL && next == PostStatus.OPEN);
        if (!allowed) throw new IllegalStateException("Undefined post transition: " + status + " -> " + next);
        this.status = next; this.updatedAt = now;
    }
    public void remove(Instant now, User actor) { transitionTo(PostStatus.REMOVED, now); this.deletedAt = now; this.deletedBy = actor; }
    public Instant deadline() { return referenceTime.minus(Duration.ofMinutes(10)); }
    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public PostType getType() { return type; }
    public String getTitle() { return title; }
    public int getCapacity() { return capacity; }
    public PostStatus getStatus() { return status; }
    public Instant getReferenceTime() { return referenceTime; }
    public Instant getDeletedAt() { return deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
