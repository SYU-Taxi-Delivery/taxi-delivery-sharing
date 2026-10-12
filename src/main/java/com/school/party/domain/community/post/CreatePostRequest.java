package com.school.party.domain.community.post;

import com.school.party.domain.community.domain.PostType;
import java.time.OffsetDateTime;

/** Author, state and derived counts are deliberately absent from the writable DTO. */
public record CreatePostRequest(PostType type, String title, String content, Integer capacity,
        OffsetDateTime referenceTime, String communicationUrl, String departure, String destination,
        String restaurantName, String pickupLocation, String orderGuide) {}
