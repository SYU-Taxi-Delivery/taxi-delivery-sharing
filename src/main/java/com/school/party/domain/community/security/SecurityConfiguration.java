package com.school.party.domain.community.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import com.school.party.domain.community.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.Clock;
import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env, ObjectMapper mapper, Clock clock) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/error", "/api/csrf", "/api/auth/local/login", "/api/auth/school-email/**").permitAll().anyRequest().authenticated());
        // Keep CSRF protection for browser/session integration; clients fetch /api/csrf before mutations.
        http.csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        http.exceptionHandling(e -> e
            .authenticationEntryPoint((req,res,ex) -> write(mapper, clock, ErrorCode.AUTHENTICATION_REQUIRED, req, res))
            .accessDeniedHandler((req,res,ex) -> write(mapper, clock, ex instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN_OPERATION, req, res)));
        http.requestCache(c -> c.disable());
        if (env.acceptsProfiles(Profiles.of("demo"))) http.httpBasic(b -> b
            .authenticationEntryPoint((req,res,ex) -> write(mapper, clock, ErrorCode.AUTHENTICATION_REQUIRED, req, res)));
        // The local login controller persists CampusPrincipal in the authenticated session.
        return http.build();
    }
    private static void write(ObjectMapper mapper, Clock clock, ErrorCode code, HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setStatus(code.status().value()); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), ApiResponses.failure(code, clock.instant(), Map.of(), req));
    }
}
