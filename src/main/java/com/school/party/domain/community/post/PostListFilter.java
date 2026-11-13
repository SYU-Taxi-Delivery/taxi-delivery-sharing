package com.school.party.domain.community.post;

import jakarta.persistence.criteria.*;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import org.springframework.data.jpa.domain.Specification;
import java.time.*;
import java.util.*;

public record PostListFilter(int page, int size, PostType type, PostStatus status, boolean allStates, PostSearch search) {
    public static PostListFilter parse(int page, int size, String type, String status, Instant now) {
        return parse(page, size, type, status, PostSearch.NONE, now);
    }
    public static PostListFilter parse(int page, int size, String type, String status, PostSearch search, Instant now) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE) throw invalid(now);
        try {
            PostType typeValue = type == null || type.equals("ALL") ? null : PostType.valueOf(type);
            PostStatus statusValue = status == null || status.equals("ALL") ? null : PostStatus.valueOf(status);
            if (statusValue == PostStatus.REMOVED) throw invalid(now);
            return new PostListFilter(page, size, typeValue, statusValue, "ALL".equals(status), search == null ? PostSearch.NONE : search);
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
            searchPredicates(root, cb, now, predicates);
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
    private void searchPredicates(Root<Post> root, CriteriaBuilder cb, Instant now, List<Predicate> predicates) {
        PostSearch s = search;
        if (s.q() != null) predicates.add(cb.or(contains(cb, root, "departure", s.q()), contains(cb, root, "destination", s.q()),
            contains(cb, root, "restaurantName", s.q()), contains(cb, root, "pickupLocation", s.q()), contains(cb, root, "title", s.q())));
        if (s.departure() != null) predicates.add(contains(cb, root, "departure", s.departure()));
        if (s.destination() != null) predicates.add(contains(cb, root, "destination", s.destination()));
        if (s.restaurant() != null) predicates.add(contains(cb, root, "restaurantName", s.restaurant()));
        if (s.pickup() != null) predicates.add(contains(cb, root, "pickupLocation", s.pickup()));
        if (s.direction() == PostSearch.Direction.TO_SCHOOL)
            predicates.add(contains(cb, root, "destination", PostSearch.normalize(PostSearch.CAMPUS)));
        if (s.direction() == PostSearch.Direction.FROM_SCHOOL)
            predicates.add(contains(cb, root, "departure", PostSearch.normalize(PostSearch.CAMPUS)));
        if (s.from() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("referenceTime"), s.from()));
        if (s.to() != null) predicates.add(cb.lessThan(root.get("referenceTime"), s.to()));
        if (s.joinable()) {
            // Same rule as Post.recruitmentStatus == RECRUITING: stored OPEN means seats are left
            // (the last join moves it to FULL), and the deadline is 10 minutes before the reference time.
            predicates.add(cb.equal(root.get("status"), PostStatus.OPEN));
            predicates.add(cb.greaterThan(root.get("referenceTime"), now.plus(Duration.ofMinutes(10))));
        }
    }
    /** Same normalization as {@link PostSearch#normalize}: no spaces, no "역", lower case. */
    private static Predicate contains(CriteriaBuilder cb, Root<Post> root, String field, String keyword) {
        Expression<String> column = root.<String>get(field);
        Expression<String> noSpace = cb.function("replace", String.class, column, cb.literal(" "), cb.literal(""));
        Expression<String> noStation = cb.function("replace", String.class, noSpace, cb.literal("역"), cb.literal(""));
        return cb.like(cb.lower(noStation), "%" + escape(keyword) + "%", '\\');
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
    private static ApiException invalid(Instant now) { return new ApiException(ErrorCode.INVALID_QUERY_PARAMETER, now); }
}
