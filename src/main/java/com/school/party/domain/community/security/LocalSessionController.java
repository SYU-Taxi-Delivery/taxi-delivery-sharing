package com.school.party.domain.community.security;

import com.school.party.domain.user.dto.LocalLoginRequest;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.service.LocalAuthService;
import com.school.party.domain.community.api.ApiException;
import com.school.party.domain.community.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

/** Bridges the team's local password validation to the session used by community APIs. */
@RestController
@RequestMapping("/api/auth/local")
public class LocalSessionController {
    private final LocalAuthService auth;
    private final Clock clock;
    public LocalSessionController(LocalAuthService auth, Clock clock) { this.auth = auth; this.clock = clock; }

    @PostMapping("/login")
    public SessionUser login(@Valid @RequestBody LocalLoginRequest input,
                            HttpServletRequest request, HttpServletResponse response) {
        com.school.party.domain.user.entity.User user;
        try { user = auth.authenticateLocal(input); }
        catch (IllegalArgumentException | IllegalStateException ex) { throw denied(); }
        if (user.getStatus() != UserStatus.ACTIVE) throw denied();
        // The user ID is resolved from persisted credentials, never from a client-supplied ID.
        CampusUser principal = new CampusUser(user.getId(), user.getSchoolEmail(), "", user.getRole());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        if (request.getSession(false) != null) request.changeSessionId();
        new CsrfAuthenticationStrategy(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
        return new SessionUser(user.getId().toString(), user.getName(), user.getRole().name());
    }
    private ApiException denied() { return new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, clock.instant()); }
    public record SessionUser(String userId, String name, String role) {}
}
