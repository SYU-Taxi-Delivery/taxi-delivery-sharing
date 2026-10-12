package com.school.party.domain.community;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommunityConfiguration {
    @Bean Clock applicationClock() { return Clock.systemUTC(); }
}
