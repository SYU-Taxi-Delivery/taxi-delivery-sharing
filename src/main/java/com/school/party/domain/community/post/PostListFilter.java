package com.school.party.domain.community.post;

import jakarta.persistence.criteria.Predicate;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.util.*;

public record PostListFilter(int page, int size, PostType type, PostStatus status, boolean allStates) {
    public static PostListFilter parse(int page, int size, String type, String status, Instant now) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE) throw invalid(now);
        try {
            PostType typeValue = type == null || type.equals("ALL") ? null : PostType.valueOf(type);
            PostStatus statusValue = status == null || status.equals("ALL") ? null : PostStatus.valueOf(status);
            if (statusValue == PostStatus.REMOVED) throw invalid(now);
            return new PostListFilter(page, size, typeValue, statusValue, "ALL".equals(status));
        } catch (IllegalArgumentException e) { throw invalid(now); }
    }
    public Specification<Post> specification(Instant now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            predicates.add(cb.notEqual(root.get("status"), PostStatus.REMOVED));
            if (type != null) predicates.add(cb.equal(root.get("type"), type));
            Predicate active = root.get("status").in(PostStatus.OPEN, PostStatus.FULL, PostStatus.CLOSED);
            if (!allStates) {
                if (status == null) {
                    predicates.add(root.get("status").in(PostStatus.OPEN, PostStatus.FULL));
                    predicates.add(cb.greaterThan(root.get("referenceTime"), now));
                } else if (status == PostStatus.FINISHED) {
                    predicates.add(cb.or(cb.equal(root.get("status"), PostStatus.FINISHED),
                        cb.and(active, cb.lessThanOrEqualTo(root.get("referenceTime"), now))));
                } else {
                    predicates.add(cb.equal(root.get("status"), status));
                    if (status != PostStatus.CANCELLED) predicates.add(cb.greaterThan(root.get("referenceTime"), now));
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
    private static ApiException invalid(Instant now) { return new ApiException(ErrorCode.INVALID_QUERY_PARAMETER, now); }
}
