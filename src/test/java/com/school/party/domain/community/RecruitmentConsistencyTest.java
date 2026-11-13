package com.school.party.domain.community;

import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.post.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.*;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.school.party.domain.community.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * One post reads the same everywhere (list, detail, my participations, my posts),
 * the public recruitment status follows one rule around the deadline, and private
 * data (communication link) never reaches viewers without access.
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class) @AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class RecruitmentConsistencyTest {
    /** 2026-11-13 (금) 23:30 KST. */
    static final Instant NOW = Instant.parse("2026-11-13T14:30:00Z");
    /** 2026-11-14 (토) 01:18 KST: after midnight in Seoul. */
    static final Instant TIME = Instant.parse("2026-11-13T16:18:00Z");
    static final Instant DEADLINE = TIME.minus(Duration.ofMinutes(10));
    static final String LINK = "https://open.kakao.com/o/test";
    @Autowired PostService service;
    @Autowired ParticipationService participation;
    @Autowired PostLifecycleService lifecycle;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired ParticipationRepository participations;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    User author, s1, s2, s3;

    private static User fixtureUser(String name) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syu.ac.kr").name(name).role(Role.STUDENT).status(UserStatus.ACTIVE).build();
    }
    @BeforeEach void arrange() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); clock.set(NOW);
        author = users.save(fixtureUser("박지민"));
        s1 = users.save(fixtureUser("오수빈")); s2 = users.save(fixtureUser("한지우")); s3 = users.save(fixtureUser("정민수"));
    }
    long taxi(User by, int capacity, Instant at, String departure, String destination) {
        return Long.parseLong(service.create(by.getId(), new CreatePostRequest(PostType.TAXI, departure + " → " + destination, "같이 가요",
            capacity, at.atOffset(ZoneOffset.ofHours(9)), LINK, departure, destination, null, null, null)).data().postId());
    }
    long delivery(User by, int capacity, Instant at, String restaurant, String pickup) {
        return Long.parseLong(service.create(by.getId(), new CreatePostRequest(PostType.DELIVERY, restaurant + " 같이 주문해요", "함께 주문해요",
            capacity, at.atOffset(ZoneOffset.ofHours(9)), LINK, null, null, restaurant, pickup, "메뉴를 알려주세요")).data().postId());
    }
    long route() { return taxi(author, 4, TIME, "삼육대학교 후문", "태릉입구역 7번 출구"); }
    RecruitmentStatus recruitment(long id, User viewer) { return service.get(id, viewer.getId()).data().recruitmentStatus(); }
    String attempt(Runnable action) { try { action.run(); return "OK"; } catch (ApiException e) { return e.code().name(); } }
    void rejects(ErrorCode code, Runnable action) { assertThat(attempt(action)).isEqualTo(code.name()); }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(User user) {
        return user(new CampusUser(user.getId(), user.getName(), "unused", user.getRole()));
    }
    List<String> compete(List<Callable<String>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size()); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> pending = new ArrayList<>();
            for (Callable<String> action : actions) pending.add(executor.submit(() -> { start.await(); return action.call(); }));
            start.countDown(); List<String> results = new ArrayList<>();
            for (Future<String> f : pending) results.add(f.get(20, TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }

    @Test void sameRecordAcrossListDetailHistoryAndMyPosts() {
        long id = route(); participation.join(id, s1.getId());
        var list = service.list(s2.getId(), 0, 20, null, null).data().items().stream().filter(i -> i.postId().equals(String.valueOf(id))).findFirst().orElseThrow();
        var detail = service.get(id, s2.getId()).data();
        var history = participation.history(s1.getId(), 0, 20, null).data().items().get(0).post();
        var mine = service.mine(author.getId(), 0, 20).data().items().get(0);

        for (var at : List.of(list.referenceTime(), detail.referenceTime(), history.referenceTime(), mine.referenceTime()))
            assertThat(at).isEqualTo(TIME.atOffset(ZoneOffset.ofHours(9)));
        assertThat(List.of(list.participationDeadline(), detail.participationDeadline(), history.participationDeadline(), mine.participationDeadline()))
            .containsOnly(DEADLINE.atOffset(ZoneOffset.ofHours(9)));
        assertThat(List.of(list.departure(), detail.departure(), history.departure(), mine.departure())).containsOnly("삼육대학교 후문");
        assertThat(List.of(list.destination(), detail.destination(), history.destination(), mine.destination())).containsOnly("태릉입구역 7번 출구");
        assertThat(List.of(list.author().displayName(), detail.author().displayName(), history.author().displayName(), mine.author().displayName())).containsOnly("박지민");
        assertThat(List.of(list.capacity(), detail.capacity(), history.capacity(), mine.capacity())).containsOnly(4);
        assertThat(List.of(list.currentMemberCount(), detail.currentMemberCount(), history.currentMemberCount(), mine.currentMemberCount())).containsOnly(2L);
        assertThat(List.of(list.recruitmentStatus(), detail.recruitmentStatus(), history.recruitmentStatus(), mine.recruitmentStatus()))
            .containsOnly(RecruitmentStatus.RECRUITING);
        // The link: only the author's own list and the member's history carry it.
        assertThat(list.communicationUrl()).isNull(); assertThat(detail.communicationUrl()).isNull();
        assertThat(history.communicationUrl()).isEqualTo(LINK); assertThat(mine.communicationUrl()).isEqualTo(LINK);
    }

    @Test void deadlineBoundaryIsTheSameForStatusAndValidation() {
        long id = route();
        clock.set(DEADLINE.minusSeconds(1));
        assertThat(recruitment(id, s1)).isEqualTo(RecruitmentStatus.RECRUITING);
        assertThat(service.get(id, s1.getId()).data().viewer().canJoin()).isTrue();
        clock.set(DEADLINE);
        assertThat(recruitment(id, s1)).isEqualTo(RecruitmentStatus.APPLICATION_CLOSED);
        assertThat(service.get(id, s1.getId()).data().viewer().canJoin()).isFalse();
        rejects(JOIN_TIME_CLOSED, () -> participation.join(id, s1.getId()));
        assertThat(service.list(s2.getId(), 0, 20, null, null, PostSearch.parse(null, null, null, null, null, null, null, null, true, null, DEADLINE))
            .data().items()).isEmpty();
        clock.set(DEADLINE.plusSeconds(1));
        assertThat(recruitment(id, s1)).isEqualTo(RecruitmentStatus.APPLICATION_CLOSED);
        clock.set(TIME);
        assertThat(recruitment(id, s1)).isEqualTo(RecruitmentStatus.FINISHED);
    }

    @Test void fullReopensWhenASeatFreesBeforeTheDeadline() {
        long id = taxi(author, 2, TIME, "삼육대학교 정문", "석계역 1번 출구");
        participation.join(id, s1.getId());
        assertThat(recruitment(id, s2)).isEqualTo(RecruitmentStatus.FULL);
        rejects(POST_FULL, () -> participation.join(id, s2.getId()));
        participation.cancel(id, s1.getId());
        assertThat(recruitment(id, s2)).isEqualTo(RecruitmentStatus.RECRUITING);
        participation.join(id, s2.getId());
        assertThat(service.get(id, s2.getId()).data().viewer().myParticipationStatus()).isEqualTo("JOINED");
        // The earlier member's own status stays "CANCELLED" while the post is FULL again.
        assertThat(service.get(id, s1.getId()).data().viewer().myParticipationStatus()).isEqualTo("CANCELLED");
        assertThat(recruitment(id, s1)).isEqualTo(RecruitmentStatus.FULL);
    }

    @Test void authorCloseKeepsMembersButScheduleCancelEndsThem() {
        long id = route(); participation.join(id, s1.getId());
        lifecycle.close(id, author.getId());
        var closed = service.get(id, s1.getId()).data();
        assertThat(closed.recruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        assertThat(closed.viewer().myParticipationStatus()).isEqualTo("JOINED");
        assertThat(closed.communicationUrl()).isEqualTo(LINK);
        rejects(POST_CLOSED, () -> participation.join(id, s2.getId()));

        lifecycle.cancelRecruitment(id, author.getId());
        var cancelled = service.get(id, s1.getId()).data();
        assertThat(cancelled.recruitmentStatus()).isEqualTo(RecruitmentStatus.CANCELLED);
        assertThat(cancelled.communicationUrl()).isNull();
        var history = participation.history(s1.getId(), 0, 20, null).data().items().get(0);
        assertThat(history.participationStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(history.post().communicationUrl()).isNull();
    }

    @Test void joinRacingAuthorCloseEndsInOneConsistentState() throws Exception {
        long id = route();
        var outcomes = compete(List.of(() -> attempt(() -> participation.join(id, s1.getId())),
            () -> attempt(() -> lifecycle.close(id, author.getId()))));
        var detail = service.get(id, s2.getId()).data();
        assertThat(detail.recruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        if (outcomes.get(0).equals("OK")) assertThat(detail.currentMemberCount()).isEqualTo(2);
        else { assertThat(outcomes.get(0)).isEqualTo("POST_CLOSED"); assertThat(detail.currentMemberCount()).isEqualTo(1); }
    }

    @Test void memberCancelRacingAuthorCloseNeverCountsTwice() throws Exception {
        long id = route(); participation.join(id, s1.getId()); participation.join(id, s2.getId());
        var outcomes = compete(List.of(() -> attempt(() -> participation.cancel(id, s1.getId())),
            () -> attempt(() -> lifecycle.close(id, author.getId()))));
        assertThat(outcomes).containsOnly("OK");
        var detail = service.get(id, s3.getId()).data();
        assertThat(detail.recruitmentStatus()).isEqualTo(RecruitmentStatus.CLOSED);
        assertThat(detail.currentMemberCount()).isEqualTo(2);
    }

    @Test void repeatedJoinTapsCountOnce() throws Exception {
        long id = route();
        var outcomes = compete(List.of(() -> attempt(() -> participation.join(id, s1.getId())),
            () -> attempt(() -> participation.join(id, s1.getId())), () -> attempt(() -> participation.join(id, s1.getId()))));
        assertThat(Collections.frequency(outcomes, "OK")).isEqualTo(1);
        assertThat(outcomes).allMatch(v -> v.equals("OK") || v.equals("ALREADY_JOINED"));
        assertThat(service.get(id, s2.getId()).data().currentMemberCount()).isEqualTo(2);
    }

    @Test void rejectedJoinReturnsTheLatestPublicStateWithoutPrivateData() throws Exception {
        long id = taxi(author, 2, TIME, "삼육대학교 정문", "석계역 1번 출구");
        long late = taxi(s3, 4, TIME, "삼육대학교 후문", "태릉입구역 7번 출구");
        participation.join(id, s1.getId());
        mvc.perform(post("/api/posts/{id}/participants", id).with(as(s2)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("POST_FULL"))
            .andExpect(jsonPath("$.error.details.post.recruitmentStatus").value("FULL"))
            .andExpect(jsonPath("$.error.details.post.remainingSeats").value(0))
            .andExpect(jsonPath("$.error.details.post.currentMemberCount").value(2))
            .andExpect(jsonPath("$.error.details.post.communicationUrl").doesNotExist())
            .andExpect(jsonPath("$.error.details.post.participants").doesNotExist());
        clock.set(DEADLINE);
        mvc.perform(post("/api/posts/{id}/participants", late).with(as(s2)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("JOIN_TIME_CLOSED"))
            .andExpect(jsonPath("$.error.details.post.recruitmentStatus").value("APPLICATION_CLOSED"));
    }

    @Test void linkAndMembersStayPrivate() throws Exception {
        long id = route(); participation.join(id, s1.getId());
        mvc.perform(get("/api/posts/{id}", id).with(as(s2))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.communicationUrl").doesNotExist())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/posts/{id}/participants", id).with(as(s2))).andExpect(status().isForbidden());
        mvc.perform(get("/api/posts/{id}", id).with(as(s1))).andExpect(jsonPath("$.data.communicationUrl").value(LINK));
        participation.cancel(id, s1.getId());
        mvc.perform(get("/api/posts/{id}", id).with(as(s1))).andExpect(jsonPath("$.data.communicationUrl").doesNotExist());
        mvc.perform(get("/api/posts/{id}/participants", id).with(as(s1))).andExpect(status().isForbidden());
        mvc.perform(get("/api/posts/{id}", id)).andExpect(status().isUnauthorized());
        participation.join(id, s1.getId());
        clock.set(TIME);
        mvc.perform(get("/api/posts/{id}", id).with(as(s1))).andExpect(jsonPath("$.data.communicationUrl").doesNotExist());
        assertThat(participation.history(s1.getId(), 0, 20, null).data().items().get(0).post().communicationUrl()).isNull();
    }

    @Test void searchMatchesPlaceSpellingVariantsDirectionTimeAndSort() {
        long a = taxi(author, 4, TIME, "삼육대학교 후문", "태릉입구역 7번 출구");
        long b = taxi(s1, 4, TIME.plus(Duration.ofHours(1)), "석계역 1번 출구", "삼육대학교 정문");
        long c = delivery(s2, 6, TIME.plus(Duration.ofHours(2)), "정문 김밥", "후문");
        long full = taxi(s3, 2, TIME.plus(Duration.ofHours(3)), "화랑대역 2번 출구", "삼육대학교 후문");
        participation.join(full, s1.getId());

        assertThat(ids(search("destination", "태릉입구"))).containsExactly(a);
        assertThat(ids(search("destination", "태릉 입구역"))).containsExactly(a);
        assertThat(ids(search("destination", "7번출구"))).containsExactly(a);
        assertThat(ids(search("q", "석계"))).containsExactly(b);
        assertThat(ids(search("restaurant", "김밥"))).containsExactly(c);
        assertThat(ids(search("pickup", "후문"))).containsExactly(c);
        assertThat(ids(query(null, null, "TO_SCHOOL", null, null, false, null))).containsExactly(b, full);
        assertThat(ids(query(null, null, "FROM_SCHOOL", null, null, false, null))).containsExactly(a);
        String from = TIME.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.ofHours(9)).toString();
        String to = TIME.plus(Duration.ofMinutes(150)).atOffset(ZoneOffset.ofHours(9)).toString();
        assertThat(ids(query(null, null, null, from, to, false, null))).containsExactly(b, c);
        assertThat(ids(query(null, null, null, null, null, true, null))).containsExactly(a, b, c);
        assertThat(ids(query(null, null, null, null, null, false, "RECENT"))).containsExactly(full, c, b, a);
        rejects(INVALID_QUERY_PARAMETER, () -> query(null, null, "UPHILL", null, null, false, null));
        rejects(INVALID_QUERY_PARAMETER, () -> query(null, null, null, to, from, false, null));
    }

    @Test void authorLifecycleAndMyPostsOverHttp() throws Exception {
        long id = route(); participation.join(id, s1.getId());
        mvc.perform(post("/api/posts/{id}/close", id).with(as(s1)).with(csrf()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
        mvc.perform(post("/api/posts/{id}/close", id).with(as(author)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.recruitmentStatus").value("CLOSED"))
            .andExpect(jsonPath("$.data.currentMemberCount").value(2));
        mvc.perform(post("/api/posts/{id}/close", id).with(as(author)).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("POST_CLOSED"));
        mvc.perform(get("/api/users/me/posts").with(as(author)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].postId").value(String.valueOf(id)))
            .andExpect(jsonPath("$.data.items[0].communicationUrl").value(LINK));
        mvc.perform(post("/api/posts/{id}/cancel", id).with(as(author)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.recruitmentStatus").value("CANCELLED"))
            .andExpect(jsonPath("$.data.currentMemberCount").value(1));
        mvc.perform(get("/api/posts").param("direction", "SIDEWAYS").with(as(s2)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get("/api/posts").param("destination", "태릉입구").param("joinable", "true").with(as(s2)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private PostResponse.PageView search(String field, String value) {
        return switch (field) {
            case "q" -> list(PostSearch.parse(value, null, null, null, null, null, null, null, false, null, NOW));
            case "destination" -> list(PostSearch.parse(null, null, value, null, null, null, null, null, false, null, NOW));
            case "restaurant" -> list(PostSearch.parse(null, null, null, value, null, null, null, null, false, null, NOW));
            case "pickup" -> list(PostSearch.parse(null, null, null, null, value, null, null, null, false, null, NOW));
            default -> throw new IllegalArgumentException(field);
        };
    }
    private PostResponse.PageView query(String departure, String destination, String direction, String from, String to, boolean joinable, String sort) {
        return list(PostSearch.parse(null, departure, destination, null, null, direction, from, to, joinable, sort, NOW));
    }
    private PostResponse.PageView list(PostSearch search) { return service.list(s3.getId(), 0, 20, null, null, search).data(); }
    private static List<Long> ids(PostResponse.PageView page) { return page.items().stream().map(i -> Long.parseLong(i.postId())).toList(); }
}
