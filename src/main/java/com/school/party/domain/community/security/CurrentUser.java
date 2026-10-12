package com.school.party.domain.community.security;
import com.school.party.domain.community.api.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import java.time.Clock;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.enums.UserStatus;
@Component
public class CurrentUser {
    private final Clock clock;
    private final UserRepository users;
    public CurrentUser(Clock clock, UserRepository users) { this.clock = clock; this.users = users; }
    public long id(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof CampusPrincipal p) || p.userId() <= 0)
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, clock.instant());
        var user = users.findById(p.userId()).orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, clock.instant()));
        if (user.getStatus() != UserStatus.ACTIVE) throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, clock.instant());
        return p.userId();
    }
}
