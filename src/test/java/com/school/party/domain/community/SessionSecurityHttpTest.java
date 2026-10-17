package com.school.party.domain.community;

import com.school.party.domain.community.repository.*;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.user.entity.*;
import com.school.party.domain.user.enums.*;
import com.school.party.domain.user.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:security-http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"test", "prod"})
@Import(SessionSecurityHttpTest.Fixtures.class)
class SessionSecurityHttpTest {
    static final String LOGIN = "{\"identifier\":\"security-test\",\"password\":\"test-password\"}";
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired LocalCredentialRepository credentials;
    @Autowired ParticipationRepository participations;
    @Autowired PostRepository posts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired SecurityFilterChain filterChain;
    SecurityHttpClient browser;
    User user;

    @BeforeEach void setUp() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch();
        credentials.deleteAllInBatch(); users.deleteAllInBatch();
        user = users.save(User.builder().studentNo("security-test").name("보안검증학생")
            .schoolEmail("security-test@syu.ac.kr").role(Role.STUDENT).status(UserStatus.ACTIVE).build());
        credentials.save(LocalCredential.builder().user(user).passwordHash(encoder.encode("test-password")).build());
        browser = new SecurityHttpClient(port, mapper);
    }

    @AfterEach void closeBrowser() { browser.close(); }

    @Test void validBasicCredentialsAreNotAcceptedOutsideDemo() throws Exception {
        assertThat(filterChain.getFilters()).noneMatch(BasicAuthenticationFilter.class::isInstance);
        String basic = "Basic " + Base64.getEncoder().encodeToString("security-test:test-password".getBytes(StandardCharsets.UTF_8));
        browser.error(browser.send("GET", "/api/posts", null, null, basic), 401, "AUTHENTICATION_REQUIRED");
        assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
        login();
        assertThat(browser.send("GET", "/api/posts", null, null).statusCode()).isEqualTo(200);
    }

    @Test void loginRotatesRealSessionIdAndRawCsrfCookie() throws Exception {
        // Test-only endpoint establishes an anonymous servlet session to exercise fixation protection.
        assertThat(browser.send("GET", "/api/auth/school-email/test-session", null, null).statusCode()).isEqualTo(200);
        String oldSession = browser.cookies.get("JSESSIONID");
        assertThat(oldSession).isNotBlank();
        var oldToken = browser.csrf();
        assertThat(browser.send("POST", "/api/auth/local/login", LOGIN, oldToken).statusCode()).isEqualTo(200);
        String newSession = browser.cookies.get("JSESSIONID");
        assertThat(newSession).isNotBlank().isNotEqualTo(oldSession);
        var newToken = browser.csrf();
        // The masked response changes on every GET; compare the underlying cookie instead.
        assertThat(newToken.rawCookie()).isNotEqualTo(oldToken.rawCookie());
        browser.error(browser.send("POST", "/api/posts", postBody(), oldToken), 403, "CSRF_INVALID");
        assertThat(posts.count()).isZero();
        assertThat(browser.send("POST", "/api/posts", postBody(), newToken).statusCode()).isEqualTo(201);
        try (var stale = new SecurityHttpClient(port, mapper)) {
            stale.cookies.put("JSESSIONID", oldSession);
            stale.error(stale.send("GET", "/api/posts", null, null), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test void freshLoginCreatesSessionAndReloginRotatesItAgain() throws Exception {
        var before = browser.csrf();
        assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
        assertThat(browser.send("POST", "/api/auth/local/login", LOGIN, before).statusCode()).isEqualTo(200);
        String first = browser.cookies.get("JSESSIONID");
        var after = browser.csrf();
        assertThat(first).isNotBlank();
        assertThat(after.rawCookie()).isNotEqualTo(before.rawCookie());
        assertThat(browser.send("POST", "/api/auth/local/login", LOGIN, after).statusCode()).isEqualTo(200);
        assertThat(browser.cookies.get("JSESSIONID")).isNotEqualTo(first);
        assertThat(browser.csrf().rawCookie()).isNotEqualTo(after.rawCookie());
    }

    @Test void csrfEndpointMasksTheSameCookieAndLoginRejectsMissingOrForgedToken() throws Exception {
        var first = browser.csrf();
        var second = browser.csrf();
        assertThat(second.rawCookie()).isEqualTo(first.rawCookie());
        assertThat(second.value()).isNotEqualTo(first.value());
        browser.error(browser.send("POST", "/api/auth/local/login", LOGIN, null), 403, "CSRF_INVALID");
        browser.error(browser.send("POST", "/api/auth/local/login", LOGIN,
            new SecurityHttpClient.Token(first.header(), "forged", "forged")), 403, "CSRF_INVALID");
        assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
        assertThat(browser.send("POST", "/api/auth/local/login", LOGIN, first).statusCode()).isEqualTo(200);
    }

    @Test void logoutRequiresCsrfThenInvalidatesServerSessionAndClearsToken() throws Exception {
        login();
        var token = browser.csrf();
        String session = browser.cookies.get("JSESSIONID");
        browser.error(browser.send("POST", "/logout", null, null), 403, "CSRF_INVALID");
        assertThat(browser.send("GET", "/api/posts", null, null).statusCode()).isEqualTo(200);
        var logout = browser.send("POST", "/logout", null, token);
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(browser.cookies).doesNotContainKey("XSRF-TOKEN");
        browser.error(browser.send("GET", "/api/posts", null, null), 401, "AUTHENTICATION_REQUIRED");
        try (var stale = new SecurityHttpClient(port, mapper)) {
            stale.cookies.put("JSESSIONID", session);
            stale.cookies.put("XSRF-TOKEN", token.rawCookie());
            stale.error(stale.send("GET", "/api/posts", null, null), 401, "AUTHENTICATION_REQUIRED");
            stale.error(stale.send("POST", "/api/posts", postBody(), token), 401, "AUTHENTICATION_REQUIRED");
        }
        assertThat(posts.count()).isZero();
        assertThat(browser.csrf().rawCookie()).isNotEqualTo(token.rawCookie());
    }

    @ParameterizedTest @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void inactiveAccountsCannotLogIn(UserStatus status) throws Exception {
        jdbc.update("update users set status=? where id=?", status.name(), user.getId());
        browser.error(browser.send("POST", "/api/auth/local/login", LOGIN, browser.csrf()), 401, "AUTHENTICATION_REQUIRED");
        assertThat(browser.cookies).doesNotContainKey("JSESSIONID");
        browser.error(browser.send("GET", "/api/posts", null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void existingSessionIsDeniedOnAllCommunityRoutesAfterAccountBecomesInactive(UserStatus status) throws Exception {
        login();
        var token = browser.csrf();
        var created = browser.send("POST", "/api/posts", postBody(), token);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = mapper.readTree(created.body()).path("data").path("postId").asText();
        jdbc.update("update users set status=? where id=?", status.name(), user.getId());
        for (String path : List.of("/api/posts", "/api/posts/" + id, "/api/posts/" + id + "/participants",
                "/api/posts/" + id + "/participants/me", "/api/users/me/participations")) {
            browser.error(browser.send("GET", path, null, null), 401, "AUTHENTICATION_REQUIRED");
        }
        browser.error(browser.send("POST", "/api/posts", postBody(), token), 401, "AUTHENTICATION_REQUIRED");
        browser.error(browser.send("PATCH", "/api/posts/" + id, "{\"title\":\"차단되어야 함\"}", token), 401, "AUTHENTICATION_REQUIRED");
        browser.error(browser.send("POST", "/api/posts/" + id + "/participants", null, token), 401, "AUTHENTICATION_REQUIRED");
        browser.error(browser.send("DELETE", "/api/posts/" + id + "/participants", null, token), 401, "AUTHENTICATION_REQUIRED");
        assertThat(posts.count()).isEqualTo(1);
        assertThat(posts.findAll().get(0).getTitle()).isEqualTo("세션 검증 모집");
        assertThat(participations.count()).isZero();
    }

    private void login() throws Exception {
        assertThat(browser.send("POST", "/api/auth/local/login", LOGIN, browser.csrf()).statusCode()).isEqualTo(200);
    }

    private String postBody() {
        String time = clock.instant().plusSeconds(3600).truncatedTo(ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC).toString();
        return mapper.writeValueAsString(Map.of("type", "TAXI", "title", "세션 검증 모집", "content", "함께 이동",
            "capacity", 4, "referenceTime", time, "departure", "석계역", "destination", "삼육대학교"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        // A working credential provider ensures the non-demo Basic test does not pass merely due to absent accounts.
        @Bean UserDetailsService testBasicUsers(UserRepository users, PasswordEncoder encoder) {
            String password = encoder.encode("test-password");
            return username -> {
                User user = users.findByStudentNo(username).orElseThrow();
                return new CampusUser(user.getId(), username, password, user.getRole());
            };
        }
        @Bean AnonymousSessionFixture anonymousSessionFixture() { return new AnonymousSessionFixture(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class AnonymousSessionFixture {
        @GetMapping("/api/auth/school-email/test-session") String session(HttpServletRequest request) {
            request.getSession(true);
            return "ok";
        }
    }
}
