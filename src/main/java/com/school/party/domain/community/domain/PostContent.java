package com.school.party.domain.community.domain;

import java.time.Instant;

/** Validated recruitment content, shared by creation and partial updates. */
public record PostContent(PostType type, String title, String content, int capacity,
        Instant referenceTime, String communicationUrl, String departure, String destination,
        String restaurantName, String pickupLocation, String orderGuide) {}
