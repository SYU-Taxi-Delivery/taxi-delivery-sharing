package com.school.party.domain.community;

import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.user.entity.*;
import com.school.party.domain.user.enums.*;
import com.school.party.domain.user.repository.*;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real sessions/HTTP with the field names and call sequence used by the frontend adapters. */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:frontend-http;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles({"test", "prod"})
@Import(TestClockConfiguration.class)
class FrontendContractHttpTest {
    static final Instant NOW = Instant.parse("2026-11-11T06:00:00Z"), REFERENCE = NOW.plusSeconds(7200);
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired LocalCredentialRepository credentials;
    @Autowired ParticipationRepository participations;
    @Autowired PostRepository posts;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableClock clock;
    User author, student;

    @BeforeEach void fixtures() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); credentials.deleteAllInBatch(); users.deleteAllInBatch();
        clock.set(NOW);
        for (String name : List.of("author", "student", "other")) {
            User user = users.save(User.builder().studentNo("frontend-" + name).name(name)
                .schoolEmail("frontend-" + name + "@syu.ac.kr").role(Role.STUDENT).status(UserStatus.ACTIVE).build());
            credentials.save(LocalCredential.builder().user(user).passwordHash(encoder.encode("contract-pass")).build());
            if (name.equals("author")) author = user;
            if (name.equals("student")) student = user;
        }
    }

    private SecurityHttpClient login(String name) throws Exception {
        var browser = new SecurityHttpClient(port, mapper);
        assertThat(browser.send("POST", "/api/auth/local/login",
            mapper.writeValueAsString(Map.of("identifier", "frontend-" + name, "password", "contract-pass")), browser.csrf()).statusCode()).isEqualTo(200);
        return browser;
    }

    private JsonNode data(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var json = mapper.readTree(response.body());
        assertThat(json.path("meta").path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(OffsetDateTime.parse(json.path("meta").path("evaluatedAt").asText()).getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        return json.path("data");
    }

    private String body(PostType type) {
        Map<String, Object> values = new LinkedHashMap<>(Map.of("type", type.name(), "title", "프론트 계약 검증",
            "content", "함께 이용해요", "capacity", 3, "referenceTime", REFERENCE.atOffset(ZoneOffset.ofHours(9)).toString(),
            "communicationUrl", "https://example.com/contract"));
        if (type == PostType.TAXI) { values.put("departure", "석계역"); values.put("destination", "삼육대학교"); }
        else { values.put("restaurantName", "음식점"); values.put("pickupLocation", "정문"); values.put("orderGuide", null); }
        return mapper.writeValueAsString(values);
    }

    @ParameterizedTest @EnumSource(PostType.class)
    void realSessionsKeepPostParticipationAndFormResponsesConsistent(PostType type) throws Exception {
        try (var owner = login("author"); var member = login("student"); var other = login("other")) {
            var response = owner.send("POST", "/api/posts", body(type), owner.csrf());
            var created = data(response, 201); String id = created.path("postId").asText(), path = "/api/posts/" + id;
            assertThat(created.path("postId").isString()).isTrue();
            assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo(path);
            assertThat(created.path("currentMemberCount").asInt()).isEqualTo(1);
            assertThat(created.path("viewer").path("isAuthor").asBoolean()).isTrue();
            var listed = data(member.send("GET", "/api/posts?type=" + type + "&page=0&size=20", null, null), 200);
            assertThat(listed.path("items").get(0).path("postId").asText()).isEqualTo(id);
            assertThat(listed.path("items").get(0).has("communicationUrl")).isFalse();
            var outsider = data(member.send("GET", path, null, null), 200);
            assertThat(outsider.has("communicationUrl")).isFalse();
            member.error(member.send("PATCH", path, "{\"title\":\"권한 없음\"}", member.csrf()), 403, "FORBIDDEN_OPERATION");
            member.error(member.send("POST", path + "/participants", "{\"userId\":1}", member.csrf()), 400, "INVALID_REQUEST");
            owner.error(owner.send("POST", path + "/participants", null, owner.csrf()), 403, "SELF_PARTICIPATION_NOT_ALLOWED");
            var first = data(member.send("POST", path + "/participants", null, member.csrf()), 201);
            String participationId = first.path("participation").path("participationId").asText();
            member.error(member.send("POST", path + "/participants", null, member.csrf()), 409, "ALREADY_JOINED");
            var names = data(member.send("GET", path + "/participants", null, null), 200);
            assertThat(names.path("author").path("displayName").asText()).isEqualTo("author");
            assertThat(names.path("participants").get(0).path("displayName").asText()).isEqualTo("student");
            owner.error(owner.send("PATCH", path, "{\"capacity\":2}", owner.csrf()), 409, "POST_CORE_FIELDS_LOCKED");
            assertThat(data(owner.send("PATCH", path, "{\"title\":\"수정 완료\"}", owner.csrf()), 200).path("title").asText()).isEqualTo("수정 완료");
            data(member.send("DELETE", path + "/participants", null, member.csrf()), 200);
            member.error(member.send("GET", path + "/participants", null, null), 403, "PARTICIPANT_LIST_FORBIDDEN");
            var rejoin = data(member.send("POST", path + "/participants", null, member.csrf()), 200);
            assertThat(rejoin.path("participation").path("participationId").asText()).isEqualTo(participationId);
            assertThat(rejoin.path("participation").path("createdAt").asText()).isEqualTo(first.path("participation").path("createdAt").asText());
            var full = data(other.send("POST", path + "/participants", null, other.csrf()), 201);
            assertThat(full.path("post").path("postStatus").asText()).isEqualTo("FULL");
            assertThat(full.path("post").path("currentMemberCount").asInt()).isEqualTo(3);
            member.error(member.send("POST", path + "/participants", null, member.csrf()), 409, "POST_FULL");
            clock.set(REFERENCE.minusSeconds(600));
            member.error(member.send("DELETE", path + "/participants", null, member.csrf()), 409, "CANCEL_TIME_CLOSED");
            assertThat(data(owner.send("PATCH", path, "{\"content\":\"마감 직전 안내 수정\"}", owner.csrf()), 200).path("referenceTime").asText()).isEqualTo(created.path("referenceTime").asText());
            assertThat(posts.findById(Long.parseLong(id)).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
            assertThat(participations.countByPost_IdAndStatus(Long.parseLong(id), ParticipationStatus.JOINED)).isEqualTo(2);
            var history = data(member.send("GET", "/api/users/me/participations?status=JOINED&page=0&size=50", null, null), 200);
            assertThat(history.path("items").get(0).path("participationId").asText()).isEqualTo(participationId);
        }
    }

    @Test @EnabledIfEnvironmentVariable(named = "FRONTEND_CONTRACT_SOURCE", matches = ".+")
    void actualFrontendAdaptersAcceptRealBackendResponses() throws Exception {
        Path log = Path.of("work/issue20-frontend-adapter-http.log").toAbsolutePath();
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(System.getenv().getOrDefault("FRONTEND_CONTRACT_NODE", "node"),
            Path.of("scripts/verify-frontend-contract.mjs").toAbsolutePath().toString(), "http://127.0.0.1:" + port,
            System.getenv("FRONTEND_CONTRACT_SOURCE"), REFERENCE.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("Frontend adapter probe timeout").isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            assertThat(posts.count()).isEqualTo(2);
            assertThat(participations.count()).isEqualTo(2);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
