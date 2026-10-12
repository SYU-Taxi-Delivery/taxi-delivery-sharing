package com.school.party.domain.community;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.time.Instant;
@TestConfiguration
public class TestClockConfiguration {
    @Bean @Primary MutableClock testClock() { return new MutableClock(Instant.parse("2026-10-06T08:00:00Z")); }
}
