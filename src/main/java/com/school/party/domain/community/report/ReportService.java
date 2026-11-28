package com.school.party.domain.community.report;

import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import static com.school.party.domain.community.api.ApiModels.*;
import static com.school.party.domain.community.api.ErrorCode.*;

/**
 * Reports follow docs/post-business-rules.md 11: someone else's post in any state but
 * REMOVED, one report per user per post, an optional detail of up to 500 characters.
 * Reports alone never hide a post; an administrator decides.
 */
@Service
public class ReportService {
    /** reason is read as text so an unknown value answers INVALID_REPORT rather than a body error. */
    public record CreateReportRequest(String reason, String detail) {}
    public record ReportView(String reportId, String postId, ReportReason reason, OffsetDateTime createdAt) {}

    private final ReportRepository reports;
    private final PostRepository posts;
    private final UserRepository users;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public ReportService(ReportRepository reports, PostRepository posts, UserRepository users,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.reports = reports; this.posts = posts; this.users = users; this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result<ReportView> report(long postId, long actorId, CreateReportRequest request) {
        ReportReason reason = reason(request.reason());
        String detail = UserText.normalize(request.detail());
        if (detail != null && detail.isEmpty()) detail = null;
        if (detail != null && UserText.length(detail) > Report.MAX_DETAIL_LENGTH) throw new ApiException(INVALID_REPORT, clock.instant());
        String finalDetail = detail;
        try {
            return transaction.execute(tx -> save(postId, actorId, reason, finalDetail));
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous reports by the same user: the unique key keeps one.
            if (reports.existsByPost_IdAndReporter_Id(postId, actorId)) throw new ApiException(DUPLICATE_REPORT, clock.instant());
            throw e;
        }
    }

    private Result<ReportView> save(long postId, long actorId, ReportReason reason, String detail) {
        Instant now = clock.instant();
        Post post = posts.findById(postId).orElse(null);
        if (post == null || post.effectiveStatus(now) == PostStatus.REMOVED) throw new ApiException(POST_NOT_FOUND, now);
        if (post.getAuthor().getId() == actorId) throw new ApiException(SELF_REPORT_NOT_ALLOWED, now);
        if (reports.existsByPost_IdAndReporter_Id(postId, actorId)) throw new ApiException(DUPLICATE_REPORT, now);
        Report saved = reports.saveAndFlush(new Report(post, users.getReferenceById(actorId), reason, detail, now));
        return new Result<>(new ReportView(saved.getId().toString(), post.getId().toString(), reason, seoul(now)), now);
    }

    private ReportReason reason(String value) {
        if (value != null) for (ReportReason r : ReportReason.values()) if (r.name().equals(value)) return r;
        throw new ApiException(INVALID_REPORT, clock.instant());
    }
}
