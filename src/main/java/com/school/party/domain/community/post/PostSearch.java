package com.school.party.domain.community.post;

import com.school.party.domain.community.api.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Optional search conditions for GET /api/posts. Every field may be null.
 *
 * <ul>
 *   <li>q: any place or restaurant (departure, destination, restaurant, pickup) or the title</li>
 *   <li>departure, destination: taxi places; restaurant, pickup: delivery places</li>
 *   <li>direction: TO_SCHOOL (destination is the campus) or FROM_SCHOOL (departure is the campus)</li>
 *   <li>from, to: reference time window, ISO-8601 with offset, from inclusive and to exclusive</li>
 *   <li>joinable: true keeps only posts a student can join right now (open, seats left, before the deadline)</li>
 *   <li>sort: TIME (soonest first, default) or RECENT (newest first)</li>
 * </ul>
 *
 * Place matching ignores spaces, letter case and the word "역", so "태릉입구",
 * "태릉입구역" and "태릉 입구" all match "태릉입구역 7번 출구", and "7번출구" matches
 * "7번 출구".
 */
public record PostSearch(String q, String departure, String destination, String restaurant, String pickup,
    Direction direction, Instant from, Instant to, boolean joinable, Sort sort) {
    public enum Direction { TO_SCHOOL, FROM_SCHOOL }
    public enum Sort { TIME, RECENT }
    /** Text every campus place name contains, normalized like the keywords. */
    public static final String CAMPUS = "삼육대";
    static final int MAX_KEYWORD = 100;

    public static final PostSearch NONE = new PostSearch(null, null, null, null, null, null, null, null, false, Sort.TIME);

    public static PostSearch parse(String q, String departure, String destination, String restaurant, String pickup,
        String direction, String from, String to, Boolean joinable, String sort, Instant now) {
        try {
            Instant fromValue = time(from), toValue = time(to);
            if (fromValue != null && toValue != null && !fromValue.isBefore(toValue)) throw invalid(now);
            return new PostSearch(keyword(q, now), keyword(departure, now), keyword(destination, now), keyword(restaurant, now),
                keyword(pickup, now), direction == null || direction.isBlank() ? null : Direction.valueOf(direction),
                fromValue, toValue, Boolean.TRUE.equals(joinable),
                sort == null || sort.isBlank() ? Sort.TIME : Sort.valueOf(sort));
        } catch (IllegalArgumentException | DateTimeParseException e) { throw invalid(now); }
    }
    /** Spaces, letter case and "역" removed: the same normalization the query applies to stored places. */
    public static String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").replace("역", "").toLowerCase(Locale.ROOT);
    }
    private static String keyword(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > MAX_KEYWORD) throw invalid(now);
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }
    private static Instant time(String value) {
        return value == null || value.isBlank() ? null : OffsetDateTime.parse(value).toInstant();
    }
    private static ApiException invalid(Instant now) { return new ApiException(ErrorCode.INVALID_QUERY_PARAMETER, now); }
}
