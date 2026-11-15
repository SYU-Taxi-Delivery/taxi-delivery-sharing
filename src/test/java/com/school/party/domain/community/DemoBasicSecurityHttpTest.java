package com.school.party.domain.community;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:demo-security-http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"demo", "test"})
class DemoBasicSecurityHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired SecurityFilterChain filterChain;
    @Autowired JdbcTemplate jdbc;

    @Test void demoBasicAcceptsSeededCredentialsAndRejectsWrongCredentials() throws Exception {
        assertThat(filterChain.getFilters()).anyMatch(BasicAuthenticationFilter.class::isInstance);
        try (var browser = new SecurityHttpClient(port, mapper)) {
            assertThat(browser.send("GET", "/api/posts", null, null, basic("demo-pass")).statusCode()).isEqualTo(200);
        }
        try (var browser = new SecurityHttpClient(port, mapper)) {
            browser.error(browser.send("GET", "/api/posts", null, null, basic("wrong")), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test void demoBasicStillRequiresCsrfOnMutations() throws Exception {
        try (var browser = new SecurityHttpClient(port, mapper)) {
            browser.error(browser.send("POST", "/api/posts/1/participants", null, null, basic("demo-pass")), 403, "CSRF_INVALID");
        }
    }

    @Test void demoBasicAlsoRejectsAnInactiveUser() throws Exception {
        jdbc.update("update users set status='SUSPENDED' where student_no='demo-student1'");
        try (var browser = new SecurityHttpClient(port, mapper)) {
            browser.error(browser.send("GET", "/api/posts", null, null, basic("demo-pass")), 401, "AUTHENTICATION_REQUIRED");
        } finally {
            jdbc.update("update users set status='ACTIVE' where student_no='demo-student1'");
        }
    }

    @Test void demoAccountsAlsoSignInWithTheSessionLogin() throws Exception {
        try (var browser = new SecurityHttpClient(port, mapper)) {
            var login = browser.send("POST", "/api/auth/local/login",
                "{\"identifier\":\"demo-student2\",\"password\":\"demo-pass\"}", browser.csrf());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(login.body()).get("name").asText()).isEqualTo("student2");
            assertThat(browser.send("GET", "/api/users/me/participations", null, null).statusCode()).isEqualTo(200);
        }
        try (var browser = new SecurityHttpClient(port, mapper)) {
            var byEmail = browser.send("POST", "/api/auth/local/login",
                "{\"identifier\":\"demo-author@syuin.ac.kr\",\"password\":\"demo-pass\"}", browser.csrf());
            assertThat(byEmail.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(byEmail.body()).get("name").asText()).isEqualTo("author");
        }
        try (var browser = new SecurityHttpClient(port, mapper)) {
            var wrong = browser.send("POST", "/api/auth/local/login",
                "{\"identifier\":\"demo-student3\",\"password\":\"wrong-pass\"}", browser.csrf());
            browser.error(wrong, 401, "AUTHENTICATION_REQUIRED");
        }
    }

    private String basic(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(("student1:" + password).getBytes(StandardCharsets.UTF_8));
    }
}
