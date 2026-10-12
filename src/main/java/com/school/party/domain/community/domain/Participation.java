package com.school.party.domain.community.domain;
import com.school.party.domain.user.entity.User;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "participation", uniqueConstraints = @UniqueConstraint(name = "uq_participation_post_user", columnNames = {"post_id", "user_id"}), indexes = {
    @Index(name = "ix_participation_post_status", columnList = "post_id,status"),
    @Index(name = "ix_participation_user_updated", columnList = "user_id,updated_at,id")})
@Check(constraints = "(status = 'JOINED' AND cancelled_at IS NULL) OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)")
public class Participation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "post_id", nullable = false) private Post post;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) private ParticipationStatus status;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant cancelledAt;
    protected Participation() {}
    public Participation(Post post, User user, Instant now) {
        this.post = post; this.user = user; this.status = ParticipationStatus.JOINED;
        this.createdAt = now; this.updatedAt = now;
    }
    public void rejoin(Instant now) { status = ParticipationStatus.JOINED; updatedAt = now; cancelledAt = null; }
    public void cancel(Instant now) { status = ParticipationStatus.CANCELLED; updatedAt = now; cancelledAt = now; }
    public Long getId() { return id; }
    public Post getPost() { return post; }
    public User getUser() { return user; }
    public ParticipationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
