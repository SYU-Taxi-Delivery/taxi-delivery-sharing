package com.school.party.domain.community.domain;
import com.school.party.domain.user.entity.User;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/** A user's report of someone else's post (#53). At most one per user per post. */
@Entity
@Table(name = "post_report", uniqueConstraints = @UniqueConstraint(name = "uq_post_report_post_reporter", columnNames = {"post_id", "reporter_id"}))
public class Report {
    /** Detail limit in Unicode code points, checked by the service. */
    public static final int MAX_DETAIL_LENGTH = 500;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "post_id", nullable = false) private Post post;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reporter_id", nullable = false) private User reporter;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 30) private ReportReason reason;
    // See Comment#content for why the H2 length is doubled.
    @Column(length = MAX_DETAIL_LENGTH * 2) private String detail;
    @Column(nullable = false) private Instant createdAt;
    protected Report() {}
    public Report(Post post, User reporter, ReportReason reason, String detail, Instant now) {
        this.post = post; this.reporter = reporter; this.reason = reason; this.detail = detail; this.createdAt = now;
    }
    public Long getId() { return id; }
    public Post getPost() { return post; }
    public User getReporter() { return reporter; }
    public ReportReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
