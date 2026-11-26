package com.school.party.domain.community.domain;
import com.school.party.domain.user.entity.User;

import jakarta.persistence.*;
import java.time.Instant;

/** A public comment on a post (#53). Deleting only marks it; deleted comments are never shown. */
@Entity
@Table(name = "post_comment", indexes = @Index(name = "ix_post_comment_post_created", columnList = "post_id,created_at,id"))
public class Comment {
    /** Content limit in Unicode code points, checked by the service. */
    public static final int MAX_LENGTH = 500;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "post_id", nullable = false) private Post post;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false) private User author;
    // V3 declares VARCHAR(500), which MySQL counts in code points. The H2 test schema counts UTF-16
    // units, so it gets room for 500 code points outside the BMP (emoji).
    @Column(nullable = false, length = MAX_LENGTH * 2) private String content;
    @Column(nullable = false) private Instant createdAt;
    private Instant deletedAt;
    protected Comment() {}
    public Comment(Post post, User author, String content, Instant now) {
        this.post = post; this.author = author; this.content = content; this.createdAt = now;
    }
    public void delete(Instant now) { this.deletedAt = now; }
    public boolean isDeleted() { return deletedAt != null; }
    public Long getId() { return id; }
    public Post getPost() { return post; }
    public User getAuthor() { return author; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
