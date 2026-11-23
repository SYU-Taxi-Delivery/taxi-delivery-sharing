package com.school.party.domain.community;

import com.school.party.domain.community.domain.Post;
import com.school.party.domain.community.repository.ParticipationRepository;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.ParticipationService;
import com.school.party.domain.community.service.PostLifecycleService;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Final server-side privacy checks (week of 2026-11-23): who can read the member list and the
 * external communication link in every post state, that no read API leaks another user's student
 * number or e-mail, and that the list's viewer field only describes the requester.
 * Runs on H2 (FinalVerificationIntegrationTest) and MySQL 8.4 (FinalVerificationMySqlIT).
 */
public abstract class FinalVerificationContract {
    protected static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    protected static final String LINK = "https://open.kakao.com/o/final-check";
    @Autowired protected ParticipationService participation;
    @Autowired protected PostLifecycleService lifecycle;
    @Autowired protected PostRepository posts;
    @Autowired protected ParticipationRepository participations;
    @Autowired protected UserRepository users;
    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    protected User author, member, leaver, outsider, admin;

    @BeforeEach void arrange() {
        clean(); clock.set(NOW);
        author = users.save(account("정하은", Role.STUDENT));
        member = users.save(account("김민수", Role.STUDENT));
        leaver = users.save(account("이서준", Role.STUDENT));
        outsider = users.save(account("박도윤", Role.STUDENT));
        admin = users.save(account("최관리", Role.ADMIN));
    }
    @AfterEach void clean() { participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); }

    private static User account(String name, Role role) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syuin.ac.kr").name(name).role(role).status(UserStatus.ACTIVE).build();
    }
    protected RequestPostProcessor as(User who) { return user(new CampusUser(who.getId(), who.getName(), "unused", who.getRole())); }
    protected Post postWithLinkAndMembers(int capacity, Duration ahead) {
        Post p = posts.save(Post.taxi(author, capacity, NOW.plus(ahead), NOW));
        jdbc.update("update post set communication_url = ? where id = ?", LINK, p.getId());
        participation.join(p.getId(), member.getId());
        participation.join(p.getId(), leaver.getId());
        participation.cancel(p.getId(), leaver.getId());
        return posts.findById(p.getId()).orElseThrow();
    }
    protected String body(User viewer, String path) throws Exception {
        var request = get(path);
        if (viewer != null) request = request.with(as(viewer));
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }
    protected int status(User viewer, String path) throws Exception {
        var request = get(path);
        if (viewer != null) request = request.with(as(viewer));
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
    protected List<String> readPaths(Post p) {
        return List.of("/api/posts", "/api/posts?status=ALL", "/api/posts/" + p.getId(), "/api/posts/" + p.getId() + "/participants",
            "/api/posts/" + p.getId() + "/participants/me", "/api/users/me/participations", "/api/users/me/posts");
    }

    /** States reached through the services; returns a label for assertion messages. */
    protected String moveTo(Post p, String state) {
        switch (state) {
            case "OPEN" -> { }
            case "CLOSED" -> lifecycle.close(p.getId(), author.getId());
            case "CANCELLED" -> lifecycle.cancelRecruitment(p.getId(), author.getId());
            case "FINISHED" -> clock.set(p.getReferenceTime().plusSeconds(60));
            case "REMOVED" -> lifecycle.remove(p.getId(), admin.getId());
            default -> throw new IllegalArgumentException(state);
        }
        return state;
    }

    // -------------------------------------------- 11/25 member list and link in every post state
    @Test void PRIV01_nonMembersAndLeaversNeverReachMemberListOrLinkInAnyState() throws Exception {
        for (String state : List.of("OPEN", "CLOSED", "CANCELLED", "FINISHED", "REMOVED")) {
            clock.set(NOW);
            Post p = postWithLinkAndMembers(4, Duration.ofHours(1));
            moveTo(p, state);
            String members = "/api/posts/" + p.getId() + "/participants";
            for (User denied : List.of(outsider, leaver, admin)) {
                assertThat(status(denied, members)).as(state + " member list for " + denied.getName()).isIn(403, 404);
                String list = body(denied, members);
                assertThat(list).as(state).doesNotContain(member.getName());
                for (String path : readPaths(p)) assertThat(body(denied, path)).as(state + " " + path + " as " + denied.getName()).doesNotContain(LINK);
            }
            assertThat(status(null, members)).isEqualTo(401);
            for (String path : readPaths(p)) assertThat(body(null, path)).as(state + " anonymous " + path).doesNotContain(LINK);
            boolean active = state.equals("OPEN") || state.equals("CLOSED");
            for (User allowed : List.of(author, member)) {
                String detail = body(allowed, "/api/posts/" + p.getId());
                if (active) assertThat(detail).as(state + " detail for " + allowed.getName()).contains(LINK);
                else for (String path : readPaths(p)) assertThat(body(allowed, path)).as(state + " " + path + " as " + allowed.getName()).doesNotContain(LINK);
            }
            if (active) {
                assertThat(status(author, members)).isEqualTo(200);
                assertThat(status(member, members)).isEqualTo(200);
            }
            clean(); arrange();
        }
    }

    @Test void PRIV02_rejoiningRestoresAccessAndLeavingAgainRemovesIt() throws Exception {
        Post p = postWithLinkAndMembers(4, Duration.ofHours(1));
        String members = "/api/posts/" + p.getId() + "/participants";
        assertThat(status(leaver, members)).isEqualTo(403);
        participation.join(p.getId(), leaver.getId());
        assertThat(status(leaver, members)).isEqualTo(200);
        assertThat(body(leaver, "/api/posts/" + p.getId())).contains(LINK);
        participation.cancel(p.getId(), leaver.getId());
        assertThat(status(leaver, members)).isEqualTo(403);
        for (String path : readPaths(p)) assertThat(body(leaver, path)).as(path).doesNotContain(LINK);
    }

    // ------------------------------------------------ 11/25 personal data in read responses
    @Test void PRIV03_noReadApiReturnsStudentNumbersOrEmailsOfAnyUser() throws Exception {
        Post p = postWithLinkAndMembers(4, Duration.ofHours(1));
        List<String> secrets = new ArrayList<>();
        for (User u : List.of(author, member, leaver, outsider, admin)) { secrets.add(u.getStudentNo()); secrets.add(u.getSchoolEmail()); }
        for (User viewer : List.of(author, member, leaver, outsider, admin)) {
            for (String path : readPaths(p)) {
                String response = body(viewer, path);
                for (String secret : secrets) assertThat(response).as(path + " as " + viewer.getName()).doesNotContain(secret);
            }
        }
    }

    @Test void PRIV04_listViewerDescribesOnlyTheRequester() throws Exception {
        Post p = postWithLinkAndMembers(4, Duration.ofHours(1));
        assertThat(viewerOf(author, p).path("isAuthor").asBoolean()).isTrue();
        assertThat(viewerOf(member, p).path("myParticipationStatus").asText()).isEqualTo("JOINED");
        assertThat(viewerOf(leaver, p).path("myParticipationStatus").asText()).isEqualTo("CANCELLED");
        assertThat(viewerOf(outsider, p).path("myParticipationStatus").asText()).isEqualTo("NONE");
        assertThat(viewerOf(outsider, p).path("isAuthor").asBoolean()).isFalse();
        assertThat(viewerOf(admin, p).path("myParticipationStatus").asText()).isEqualTo("NONE");
    }
    private JsonNode viewerOf(User who, Post p) throws Exception {
        for (JsonNode item : mapper.readTree(body(who, "/api/posts")).path("data").path("items"))
            if (item.path("postId").asText().equals(p.getId().toString())) return item.path("viewer");
        throw new AssertionError("post not listed for " + who.getName());
    }
}
