package com.school.party.domain.community.post;

import tools.jackson.databind.JsonNode;
import com.school.party.domain.community.api.ApiException;
import com.school.party.domain.community.domain.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.school.party.domain.community.api.ErrorCode.*;

@Component
public class PostValidator {
    private static final Set<String> EDITABLE = Set.of("title", "content", "capacity", "referenceTime",
        "communicationUrl", "departure", "destination", "restaurantName", "pickupLocation", "orderGuide");

    public PostContent create(CreatePostRequest request, Instant now) {
        if (request == null) throw new ApiException(INVALID_REQUEST, now);
        return validate(new PostContent(request.type(), request.title(), request.content(),
            request.capacity() == null ? 0 : request.capacity(), instant(request.referenceTime()),
            request.communicationUrl(), request.departure(), request.destination(),
            request.restaurantName(), request.pickupLocation(), request.orderGuide()), now);
    }

    public PostContent update(UpdatePostRequest request, PostContent previous, Instant now) {
        JsonNode body = request.body();
        if (body == null || !body.isObject() || body.isEmpty()) throw new ApiException(INVALID_REQUEST, now);
        if (body.has("type")) throw new ApiException(POST_TYPE_IMMUTABLE, now);
        body.propertyNames().forEach(name -> { if (!EDITABLE.contains(name)) throw field(name, now); });
        int capacity = previous.capacity();
        if (body.has("capacity")) {
            JsonNode value = body.get("capacity");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw field("capacity", now);
            capacity = value.intValue();
        }
        Instant referenceTime = previous.referenceTime();
        if (body.has("referenceTime")) {
            String value = string(body, "referenceTime", null, now);
            try { referenceTime = OffsetDateTime.parse(value).toInstant(); }
            catch (DateTimeException | NullPointerException e) { throw field("referenceTime", now); }
        }
        return validate(new PostContent(previous.type(), string(body, "title", previous.title(), now),
            string(body, "content", previous.content(), now), capacity, referenceTime,
            string(body, "communicationUrl", previous.communicationUrl(), now), string(body, "departure", previous.departure(), now),
            string(body, "destination", previous.destination(), now), string(body, "restaurantName", previous.restaurantName(), now),
            string(body, "pickupLocation", previous.pickupLocation(), now), string(body, "orderGuide", previous.orderGuide(), now)), now);
    }

    private PostContent validate(PostContent input, Instant now) {
        PostContent v = new PostContent(input.type(), strip(input.title()), strip(input.content()), input.capacity(), input.referenceTime(),
            strip(input.communicationUrl()), strip(input.departure()), strip(input.destination()), strip(input.restaurantName()),
            strip(input.pickupLocation()), strip(input.orderGuide()));
        if (v.type() == null) throw field("type", now);
        text(v.title(), "title", 60, true, now); text(v.content(), "content", 1000, true, now);
        text(v.communicationUrl(), "communicationUrl", 500, false, now); text(v.orderGuide(), "orderGuide", 500, false, now);
        if (v.capacity() < 2 || v.capacity() > (v.type() == PostType.TAXI ? 4 : 10)) throw field("capacity", now);
        if (v.referenceTime() == null || !v.referenceTime().equals(v.referenceTime().truncatedTo(ChronoUnit.MINUTES))) throw field("referenceTime", now);
        if (v.type() == PostType.TAXI) {
            text(v.departure(), "departure", 100, true, now); text(v.destination(), "destination", 100, true, now);
            if (v.restaurantName() != null || v.pickupLocation() != null || v.orderGuide() != null) throw field("deliveryFields", now);
        } else {
            text(v.restaurantName(), "restaurantName", 100, true, now); text(v.pickupLocation(), "pickupLocation", 100, true, now);
            if (v.departure() != null || v.destination() != null) throw field("taxiFields", now);
        }
        if (v.communicationUrl() != null) {
            try {
                URI url = URI.create(v.communicationUrl());
                if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null) throw field("communicationUrl", now);
            } catch (IllegalArgumentException e) { throw field("communicationUrl", now); }
        }
        return v;
    }
    public boolean coreChanged(PostContent a, PostContent b) {
        return a.capacity() != b.capacity() || !a.referenceTime().equals(b.referenceTime())
            || !Objects.equals(a.departure(), b.departure()) || !Objects.equals(a.destination(), b.destination())
            || !Objects.equals(a.restaurantName(), b.restaurantName()) || !Objects.equals(a.pickupLocation(), b.pickupLocation());
    }
    public void future(Instant time, Instant now) {
        if (!time.isAfter(now.plusSeconds(600))) throw new ApiException(POST_REFERENCE_TIME_TOO_EARLY, now);
    }
    private static String strip(String s) { return s == null ? null : s.strip(); }
    private static Instant instant(OffsetDateTime t) { return t == null ? null : t.toInstant(); }
    private static String string(JsonNode body, String name, String previous, Instant now) {
        if (!body.has(name)) return previous;
        JsonNode value = body.get(name);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw field(name, now);
        return value.textValue();
    }
    private static void text(String value, String name, int limit, boolean required, Instant now) {
        if ((required && value == null) || (value != null && (value.isBlank() || value.codePointCount(0, value.length()) > limit))) throw field(name, now);
    }
    private static ApiException field(String name, Instant now) { return new ApiException(INVALID_POST_FIELD, now, Map.of("field", name)); }
}
