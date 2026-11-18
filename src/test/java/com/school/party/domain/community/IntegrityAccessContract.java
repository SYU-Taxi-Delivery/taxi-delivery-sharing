package com.school.party.domain.community;

import com.school.party.domain.community.api.ApiException;
import com.school.party.domain.community.domain.Post;
import com.school.party.domain.community.domain.PostStatus;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Weekly DB / access / concurrency checks that complement the existing contracts:
 * DB-level integrity of posts and participations after mixed concurrent operations,
 * agreement between API reads and stored rows, and server-side access rules for the
 * author close/cancel, member list, communication link and "my" endpoints.
 * Runs on H2 (IntegrityAccessIntegrationTest) and MySQL 8.4 (IntegrityAccessMySqlIT).
 */
public abstract class IntegrityAccessContract {
    protected static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    protected static final String LINK = "https://open.kakao.com/o/integrity";
    @Autowired protected ParticipationService participation;
    @Autowired protected PostLifecycleService lifecycle;
    @Autowired protected PostRepository posts;
    @Autowired protected ParticipationRepository participations;
    @Autowired protected UserRepository users;
    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    protected User author, admin;
    protected final List<User> students = new ArrayList<>();

    @BeforeEach void arrange() {
        clean(); clock.set(NOW); students.clear();
        author = users.save(account("작성자", Role.STUDENT, UserStatus.ACTIVE));
        admin = users.save(account("관리자", Role.ADMIN, UserStatus.ACTIVE));
        for (int i = 0; i < 6; i++) students.add(users.save(account("학생" + i, Role.STUDENT, UserStatus.ACTIVE)));
    }
    @AfterEach void clean() { participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); }

    private static User account(String name, Role role, UserStatus status) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syuin.ac.kr").name(name).role(role).status(status).build();
    }
    protected Post taxi(int capacity, Duration ahead) { return posts.save(Post.taxi(author, capacity, NOW.plus(ahead), NOW)); }
    protected Post withLink(Post post) {
        jdbc.update("update post set communication_url = ? where id = ?", LINK, post.getId());
        return posts.findById(post.getId()).orElseThrow();
    }
    protected RequestPostProcessor as(User who) { return user(new CampusUser(who.getId(), who.getName(), "unused", who.getRole())); }
    protected String attempt(Runnable action) {
        try { action.run(); return "OK"; }
        catch (ApiException e) { return e.code().name(); }
        catch (RuntimeException e) { return "UNEXPECTED:" + e.getClass().getSimpleName(); }
    }
    protected static List<String> compete(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) results.add(f.get(120, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }
    protected long joined(long postId) {
        return jdbc.queryForObject("select count(*) from participation where post_id = ? and status = 'JOINED'", Long.class, postId);
    }

    /** Every query lists rows that break a stored-data rule; all must be empty. */
    protected Map<String, List<Map<String, Object>>> integrityViolations() {
        String joinedCount = "(select post_id, count(*) c from participation where status = 'JOINED' group by post_id)";
        return Map.of(
            "정원 초과 (참가자+작성자 > 정원)", jdbc.queryForList("select p.id, p.capacity, coalesce(j.c,0) joined from post p left join "
                + joinedCount + " j on j.post_id = p.id where coalesce(j.c,0) + 1 > p.capacity"),
            "FULL인데 자리가 남음", jdbc.queryForList("select p.id from post p left join " + joinedCount
                + " j on j.post_id = p.id where p.status = 'FULL' and coalesce(j.c,0) + 1 <> p.capacity"),
            "OPEN인데 정원이 참", jdbc.queryForList("select p.id from post p left join " + joinedCount
                + " j on j.post_id = p.id where p.status = 'OPEN' and coalesce(j.c,0) + 1 >= p.capacity"),
            "취소·삭제된 모집에 참여 중인 사람", jdbc.queryForList("select pa.id from participation pa join post p on p.id = pa.post_id "
                + "where pa.status = 'JOINED' and p.status in ('CANCELLED','REMOVED')"),
            "작성자의 자기 모집 참여 기록", jdbc.queryForList("select pa.id from participation pa join post p on p.id = pa.post_id where pa.user_id = p.author_id"),
            "같은 사용자·모집 중복 기록", jdbc.queryForList("select post_id, user_id from participation group by post_id, user_id having count(*) > 1"),
            "상태와 취소 시각 불일치", jdbc.queryForList("select id from participation where (status = 'JOINED' and cancelled_at is not null) "
                + "or (status = 'CANCELLED' and cancelled_at is null) or status not in ('JOINED','CANCELLED')"),
            "모집·사용자가 없는 참여 기록", jdbc.queryForList("select pa.id from participation pa left join post p on p.id = pa.post_id "
                + "left join users u on u.id = pa.user_id where p.id is null or u.id is null"));
    }
    protected void assertIntegrity() {
        integrityViolations().forEach((rule, rows) -> assertThat(rows).as(rule).isEmpty());
    }

    // ------------------------------------------------- ① the checker itself detects broken rows
    @Test void INT00_integrityCheckerReportsDeliberatelyBrokenRows() {
        Post spare = taxi(4, Duration.ofHours(1)), over = taxi(2, Duration.ofHours(2));
        assertIntegrity();
        // Bypass the services on purpose: a FULL post with free seats and a post over capacity.
        jdbc.update("update post set status = 'FULL' where id = ?", spare.getId());
        for (User s : students.subList(0, 2)) jdbc.update("insert into participation(post_id, user_id, status, created_at, updated_at) "
            + "values (?, ?, 'JOINED', ?, ?)", over.getId(), s.getId(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        var found = integrityViolations();
        assertThat(found.get("FULL인데 자리가 남음")).hasSize(1);
        assertThat(found.get("정원 초과 (참가자+작성자 > 정원)")).hasSize(1);
        assertThat(found.get("OPEN인데 정원이 참")).hasSize(1);
    }

    // ------------------------------------------------- ① + ③ data integrity under concurrency
    @Test void INT01_mixedConcurrentJoinCancelCloseKeepsEveryStoredRule() throws Exception {
        Post p1 = taxi(2, Duration.ofHours(1)), p2 = taxi(3, Duration.ofHours(2)), p3 = taxi(4, Duration.ofHours(3));
        Post p4 = posts.save(Post.delivery(author, 3, NOW.plus(Duration.ofHours(4)), NOW));
        List<Post> all = List.of(p1, p2, p3, p4);
        // Round 1: every student joins every post while the author closes p2 and cancels p3.
        List<Callable<String>> round1 = new ArrayList<>();
        for (Post p : all) for (User s : students) round1.add(() -> attempt(() -> participation.join(p.getId(), s.getId())));
        round1.add(() -> attempt(() -> lifecycle.close(p2.getId(), author.getId())));
        round1.add(() -> attempt(() -> lifecycle.cancelRecruitment(p3.getId(), author.getId())));
        List<String> r1 = compete(round1);
        assertThat(r1).noneMatch(v -> v.startsWith("UNEXPECTED"));
        assertIntegrity();
        // Round 2: members leave and rejoin, others try again, a second close arrives.
        List<Callable<String>> round2 = new ArrayList<>();
        for (User s : students) {
            round2.add(() -> attempt(() -> participation.cancel(p1.getId(), s.getId())));
            round2.add(() -> attempt(() -> participation.join(p1.getId(), s.getId())));
            round2.add(() -> attempt(() -> participation.cancel(p4.getId(), s.getId())));
            round2.add(() -> attempt(() -> participation.join(p4.getId(), s.getId())));
        }
        round2.add(() -> attempt(() -> lifecycle.close(p4.getId(), author.getId())));
        List<String> r2 = compete(round2);
        assertThat(r2).noneMatch(v -> v.startsWith("UNEXPECTED"));
        assertIntegrity();
        assertThat(posts.findById(p3.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.CANCELLED);
        assertThat(joined(p3.getId())).isZero();
        assertThat(joined(p1.getId())).isLessThanOrEqualTo(1);
    }

    @Test void INT02_rapidAlternatingRequestsBySameUserLeaveOneConsistentRecord() throws Exception {
        Post p = taxi(3, Duration.ofHours(1));
        User s = students.get(0);
        List<Callable<String>> taps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            boolean join = i % 2 == 0;
            taps.add(() -> attempt(() -> { if (join) participation.join(p.getId(), s.getId()); else participation.cancel(p.getId(), s.getId()); }));
        }
        assertThat(compete(taps)).noneMatch(v -> v.startsWith("UNEXPECTED"));
        assertThat(jdbc.queryForObject("select count(*) from participation where post_id = ? and user_id = ?", Long.class, p.getId(), s.getId()))
            .isLessThanOrEqualTo(1);
        assertIntegrity();
    }

    // ------------------------------------------------------ ① API reads agree with stored rows
    @Test void INT03_listDetailAndMyViewsMatchStoredRowsAfterRaces() throws Exception {
        Post a = taxi(3, Duration.ofHours(1)), b = taxi(4, Duration.ofHours(2));
        List<Callable<String>> tasks = new ArrayList<>();
        for (User s : students) { tasks.add(() -> attempt(() -> participation.join(a.getId(), s.getId())));
            tasks.add(() -> attempt(() -> participation.join(b.getId(), s.getId()))); }
        compete(tasks);
        assertIntegrity();
        for (Post p : List.of(a, b)) {
            long members = joined(p.getId()) + 1;
            JsonNode detail = body(mvc.perform(get("/api/posts/" + p.getId()).with(as(author))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("data");
            assertThat(detail.path("currentMemberCount").asLong()).isEqualTo(members);
            assertThat(detail.path("remainingSeats").asLong()).isEqualTo(p.getCapacity() - members);
            assertThat(detail.path("postStatus").asText()).isEqualTo(stored(p.getId()));
        }
        JsonNode list = body(mvc.perform(get("/api/posts").with(as(admin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode item : list.path("data").path("items")) {
            long id = item.path("postId").asLong();
            assertThat(item.path("currentMemberCount").asLong()).as("list count of " + id).isEqualTo(joined(id) + 1);
        }
        for (User s : students) {
            JsonNode history = body(mvc.perform(get("/api/users/me/participations").with(as(s))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).path("data");
            long stored = jdbc.queryForObject("select count(*) from participation where user_id = ?", Long.class, s.getId());
            assertThat(history.path("totalElements").asLong()).as("history of " + s.getName()).isEqualTo(stored);
        }
        JsonNode mine = body(mvc.perform(get("/api/users/me/posts").with(as(author))).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString()).path("data");
        assertThat(mine.path("totalElements").asLong()).isEqualTo(2);
    }
    @Test void INT04_searchTreatsWildcardCharactersAsPlainText() throws Exception {
        taxi(4, Duration.ofHours(1)); taxi(4, Duration.ofHours(2));
        for (String raw : List.of("%", "_", "%%", "\\", "\\%", "'")) {
            JsonNode page = body(mvc.perform(get("/api/posts").param("q", raw).with(as(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
            assertThat(page.path("totalElements").asLong()).as("q=" + raw).isZero();
        }
        JsonNode control = body(mvc.perform(get("/api/posts").param("q", "석계").with(as(admin))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(control.path("totalElements").asLong()).isEqualTo(2);
    }
    private JsonNode body(String json) { return mapper.readTree(json); }
    private String stored(long postId) { return jdbc.queryForObject("select status from post where id = ?", String.class, postId); }

    // ------------------------------------------------------------- ② server-side access rules
    @Test void ACC01_onlyTheAuthorCanCloseOrCancelAndRejectedCallsChangeNothing() throws Exception {
        Post p = taxi(4, Duration.ofHours(1));
        User member = students.get(0), outsider = students.get(1);
        participation.join(p.getId(), member.getId());
        for (String action : List.of("close", "cancel")) {
            String path = "/api/posts/" + p.getId() + "/" + action;
            mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(as(outsider)).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
            mvc.perform(post(path).with(as(member)).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
            mvc.perform(post(path).with(as(admin)).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
            mvc.perform(post(path).with(as(author))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
            assertThat(stored(p.getId())).isEqualTo("OPEN");
            assertThat(joined(p.getId())).isEqualTo(1);
        }
        mvc.perform(post("/api/posts/" + p.getId() + "/close").with(as(author)).with(csrf())).andExpect(status().isOk());
        assertThat(stored(p.getId())).isEqualTo("CLOSED");
        assertThat(joined(p.getId())).isEqualTo(1);
    }

    @Test void ACC02_memberListAndLinkAreVisibleOnlyToAuthorAndCurrentMembers() throws Exception {
        Post p = withLink(taxi(4, Duration.ofHours(1)));
        User member = students.get(0), leaver = students.get(1), outsider = students.get(2);
        participation.join(p.getId(), member.getId());
        participation.join(p.getId(), leaver.getId());
        participation.cancel(p.getId(), leaver.getId());
        String members = "/api/posts/" + p.getId() + "/participants", detail = "/api/posts/" + p.getId();
        mvc.perform(get(members)).andExpect(status().isUnauthorized());
        for (User denied : List.of(outsider, leaver, admin)) {
            String response = mvc.perform(get(members).with(as(denied))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FORBIDDEN")).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(member.getName());
            mvc.perform(get(detail).with(as(denied))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.communicationUrl").doesNotExist())
                .andExpect(jsonPath("$.data.viewer.canViewCommunicationUrl").value(false));
        }
        for (User allowed : List.of(author, member)) {
            mvc.perform(get(members).with(as(allowed))).andExpect(status().isOk());
            mvc.perform(get(detail).with(as(allowed))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.communicationUrl").value(LINK))
                .andExpect(jsonPath("$.data.viewer.canViewCommunicationUrl").value(true));
        }
        // The public list never carries the link; "my" views carry it only for the author / a current member.
        for (User viewer : List.of(member, leaver, outsider, admin)) {
            assertThat(mvc.perform(get("/api/posts").with(as(viewer))).andReturn().getResponse().getContentAsString()).doesNotContain(LINK);
            assertThat(mvc.perform(get("/api/users/me/posts").with(as(viewer))).andReturn().getResponse().getContentAsString())
                .doesNotContain(LINK);
        }
        for (User denied : List.of(leaver, outsider, admin)) {
            assertThat(mvc.perform(get("/api/users/me/participations").with(as(denied))).andReturn().getResponse().getContentAsString())
                .doesNotContain(LINK);
        }
        assertThat(mvc.perform(get("/api/users/me/participations").with(as(member))).andReturn().getResponse().getContentAsString())
            .contains(LINK);
        // After the schedule is cancelled nobody, including the author, receives the link.
        lifecycle.cancelRecruitment(p.getId(), author.getId());
        for (User viewer : List.of(author, member)) {
            mvc.perform(get(detail).with(as(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.data.communicationUrl").doesNotExist());
            assertThat(mvc.perform(get("/api/users/me/posts").with(as(viewer))).andReturn().getResponse().getContentAsString()).doesNotContain(LINK);
            assertThat(mvc.perform(get("/api/users/me/participations").with(as(viewer))).andReturn().getResponse().getContentAsString())
                .doesNotContain(LINK);
        }
    }

    @Test void ACC03_myEndpointsNeverReturnOtherUsersData() throws Exception {
        Post mine = taxi(4, Duration.ofHours(1));
        User other = students.get(0), joiner = students.get(1);
        Post theirs = posts.save(Post.taxi(other, 4, NOW.plus(Duration.ofHours(2)), NOW));
        participation.join(mine.getId(), joiner.getId());
        String authorPosts = mvc.perform(get("/api/users/me/posts").with(as(author))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body(authorPosts).path("data").path("totalElements").asLong()).isEqualTo(1);
        assertThat(body(authorPosts).path("data").path("items").get(0).path("postId").asText()).isEqualTo(mine.getId().toString());
        String otherHistory = mvc.perform(get("/api/users/me/participations").with(as(other))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body(otherHistory).path("data").path("totalElements").asLong()).isZero();
        String joinerHistory = mvc.perform(get("/api/users/me/participations").with(as(joiner))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body(joinerHistory).path("data").path("totalElements").asLong()).isEqualTo(1);
        assertThat(joinerHistory).doesNotContain(theirs.getId().toString() + "\"");
        mvc.perform(get("/api/users/me/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/participations")).andExpect(status().isUnauthorized());
    }

    @Test void ACC04_suspendedOrWithdrawnSessionIsRejectedOnEveryNewEndpoint() throws Exception {
        Post p = taxi(4, Duration.ofHours(1));
        User member = students.get(0);
        participation.join(p.getId(), member.getId());
        for (UserStatus blocked : List.of(UserStatus.SUSPENDED, UserStatus.WITHDRAWN)) {
            jdbc.update("update users set status = ? where id in (?, ?)", blocked.name(), author.getId(), member.getId());
            mvc.perform(post("/api/posts/" + p.getId() + "/close").with(as(author)).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/posts/" + p.getId() + "/cancel").with(as(author)).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/users/me/posts").with(as(author))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/posts/" + p.getId() + "/participants").with(as(member))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/users/me/participations").with(as(member))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/posts/" + p.getId()).with(as(member))).andExpect(status().isUnauthorized());
            assertThat(stored(p.getId())).isEqualTo("OPEN");
            jdbc.update("update users set status = 'ACTIVE' where id in (?, ?)", author.getId(), member.getId());
        }
    }
}
