package com.school.party.domain.community;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;

import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.post.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.ParticipationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Issue #53: comment and report API behaviour over HTTP, shared by H2 and MySQL. */
public abstract class CommentReportContract {
    static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    static final Instant TIME = NOW.plusSeconds(3600);
    @Autowired MockMvc mvc;
    @Autowired PostService postService;
    @Autowired ParticipationService participation;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired ParticipationRepository participations;
    @Autowired CommentRepository comments;
    @Autowired ReportRepository reports;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    User author, student, other;
    long post;

    private static User fixtureUser(String name) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syu.ac.kr").name(name).role(Role.STUDENT).status(UserStatus.ACTIVE).build();
    }
    private void clean() {
        comments.deleteAllInBatch(); reports.deleteAllInBatch();
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch();
    }
    @BeforeEach void arrange() {
        clean(); clock.set(NOW);
        author = users.save(fixtureUser("작성자"));
        student = users.save(fixtureUser("학생"));
        other = users.save(fixtureUser("다른학생"));
        post = Long.parseLong(postService.create(author.getId(), new CreatePostRequest(PostType.TAXI, "택시 동승", "정문에서 만나요", 4,
            TIME.atOffset(ZoneOffset.ofHours(9)), "https://example.com/room", "석계역", "삼육대학교", null, null, null)).data().postId());
    }
    // Other test classes delete posts and users without knowing about these tables.
    @AfterEach void cleanUp() { clean(); }

    RequestPostProcessor as(User user) { return user(new CampusUser(user.getId(), user.getName(), "unused", user.getRole())); }
    ResultActions comment(User user, long postId, String json) throws Exception {
        return mvc.perform(post("/api/posts/" + postId + "/comments").with(as(user)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    ResultActions write(User user, String content) throws Exception {
        return comment(user, post, "{\"content\":" + quote(content) + "}");
    }
    ResultActions list(User user) throws Exception { return mvc.perform(get("/api/posts/" + post + "/comments").with(as(user))); }
    ResultActions delete(User user, String commentId) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/posts/" + post + "/comments/" + commentId)
            .with(as(user)).with(csrf()));
    }
    ResultActions report(User user, String json) throws Exception {
        return mvc.perform(post("/api/posts/" + post + "/reports").with(as(user)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    String id(ResultActions created) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.data.commentId");
    }
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\t') b.append("\\t");
            else b.append(c);
        }
        return b.append('"').toString();
    }
    void state(PostStatus status) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> posts.findByIdForUpdate(post).orElseThrow().transitionTo(status, NOW));
    }
    void remove() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> posts.findByIdForUpdate(post).orElseThrow().remove(NOW, author));
    }

    // ---- comments ----

    @Test void anySignedInStudentCommentsWithoutJoiningAndSeesTheSameList() throws Exception {
        write(student, "  저도 석계역이에요!  \n").andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("/api/posts/" + post + "/comments/")))
            .andExpect(jsonPath("$.data.content").value("저도 석계역이에요!"))
            .andExpect(jsonPath("$.data.authorName").value("학생"))
            .andExpect(jsonPath("$.data.isMine").value(true))
            .andExpect(jsonPath("$.data.createdAt").value(endsWith("+09:00")));
        clock.set(NOW.plusSeconds(60));
        write(author, "네 정문 앞에서 봬요\n10분 전 도착").andExpect(status().isCreated());

        list(other).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content", hasSize(2)))
            .andExpect(jsonPath("$.data.content[0].authorName").value("학생"))
            .andExpect(jsonPath("$.data.content[0].isMine").value(false))
            .andExpect(jsonPath("$.data.content[1].content").value("네 정문 앞에서 봬요\n10분 전 도착"))
            .andExpect(jsonPath("$.meta.requestId").exists());
        list(author).andExpect(jsonPath("$.data.content[1].isMine").value(true));
        assertThat(participations.count()).isZero();
    }

    @Test void sameTimeCommentsKeepInsertionOrder() throws Exception {
        String first = id(write(student, "첫째")), second = id(write(other, "둘째")), third = id(write(student, "셋째"));
        list(author).andExpect(jsonPath("$.data.content[*].commentId", contains(first, second, third)));
    }

    @Test void contentIsNormalizedAndLimitedTo500CodePoints() throws Exception {
        String emoji = "😀".repeat(500);
        write(student, emoji).andExpect(status().isCreated());
        write(student, "가".repeat(500)).andExpect(status().isCreated());
        // Decomposed 가 (U+1100 U+1161) counts as one character after NFC.
        write(student, "가".repeat(500)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.content").value("가".repeat(500)));
        write(student, "가".repeat(501)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COMMENT"));
        write(student, " \n\t ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COMMENT"));
        comment(student, post, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COMMENT"));
        comment(student, post, "{\"content\":123}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        comment(student, post, "not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(comments.count()).isEqualTo(3);
    }

    @Test void htmlIsStoredAsPlainText() throws Exception {
        write(student, "<script>alert(1)</script>").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.content").value("<script>alert(1)</script>"));
    }

    @Test void writingIsAllowedWhileOpenFullOrClosedBeforeTheReferenceTime() throws Exception {
        state(PostStatus.FULL); write(student, "정원 찼네요").andExpect(status().isCreated());
        state(PostStatus.CLOSED); write(student, "마감됐어도 질문").andExpect(status().isCreated());
        clock.set(TIME.minusSeconds(60)); write(student, "1분 전").andExpect(status().isCreated());
        clock.set(TIME); write(student, "기준시각").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMENT_CLOSED"));
        assertThat(comments.count()).isEqualTo(3);
    }

    @Test void cancelledAndFinishedPostsKeepCommentsReadableAndDeletableButRefuseNewOnes() throws Exception {
        String mine = id(write(student, "취소 전 댓글"));
        state(PostStatus.CANCELLED);
        write(student, "취소 후").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMENT_CLOSED"));
        list(other).andExpect(status().isOk()).andExpect(jsonPath("$.data.content", hasSize(1)));
        delete(student, mine).andExpect(status().isNoContent());
        list(other).andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test void removedOrMissingPostsShowNothing() throws Exception {
        write(student, "숨김 전").andExpect(status().isCreated());
        remove();
        list(student).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        write(student, "숨김 후").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        comment(student, post + 1000, "{\"content\":\"없는 글\"}").andExpect(status().isNotFound());
        mvc.perform(get("/api/posts/abc/comments").with(as(student))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_POST_ID"));
    }

    @Test void onlyTheWriterDeletesAndDeletedCommentsDisappear() throws Exception {
        String mine = id(write(student, "내 댓글"));
        delete(author, mine).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMENT_FORBIDDEN"));
        delete(other, mine).andExpect(status().isForbidden());
        delete(student, mine).andExpect(status().isNoContent());
        delete(student, mine).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        list(student).andExpect(jsonPath("$.data.content", hasSize(0)));
        // Soft delete: the row stays for moderation.
        assertThat(comments.findAll()).singleElement().satisfies(c -> assertThat(c.getDeletedAt()).isEqualTo(NOW));
        delete(student, "0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_COMMENT_ID"));
        delete(student, "99999").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    @Test void aCommentIsOnlyReachableThroughItsOwnPost() throws Exception {
        long second = Long.parseLong(postService.create(other.getId(), new CreatePostRequest(PostType.DELIVERY, "배달", "같이 시켜요", 4,
            TIME.atOffset(ZoneOffset.ofHours(9)), null, null, null, "음식점", "정문", null)).data().postId());
        String elsewhere = id(comment(student, second, "{\"content\":\"다른 글 댓글\"}"));
        delete(student, elsewhere).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
        list(student).andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test void anonymousAndCsrfLessRequestsAreRefused() throws Exception {
        mvc.perform(get("/api/posts/" + post + "/comments")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/posts/" + post + "/comments").with(as(student)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"csrf 없음\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/posts/" + post + "/reports").with(as(student)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"OTHER\"}")).andExpect(status().isForbidden());
        assertThat(comments.count()).isZero(); assertThat(reports.count()).isZero();
    }

    // ---- reports ----

    @Test void reportIsStoredOnceAndShownAsReportedInTheDetail() throws Exception {
        mvc.perform(get("/api/posts/" + post).with(as(student))).andExpect(jsonPath("$.data.viewer.reported").value(false));
        report(student, "{\"reason\":\"FALSE_RECRUITMENT\",\"detail\":\"  출발지가 달라요 \"}").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.postId").value(String.valueOf(post)))
            .andExpect(jsonPath("$.data.reason").value("FALSE_RECRUITMENT"))
            .andExpect(jsonPath("$.data.reportId").exists());
        assertThat(reports.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getDetail()).isEqualTo("출발지가 달라요"); assertThat(r.getReason()).isEqualTo(ReportReason.FALSE_RECRUITMENT);
        });
        mvc.perform(get("/api/posts/" + post).with(as(student))).andExpect(jsonPath("$.data.viewer.reported").value(true));
        mvc.perform(get("/api/posts/" + post).with(as(other))).andExpect(jsonPath("$.data.viewer.reported").value(false));
        report(student, "{\"reason\":\"OTHER\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DUPLICATE_REPORT"));
        // A report never changes the post by itself.
        mvc.perform(get("/api/posts/" + post).with(as(other))).andExpect(jsonPath("$.data.postStatus").value("OPEN"));
    }

    @Test void detailIsOptionalAndLimited() throws Exception {
        report(student, "{\"reason\":\"ABUSE\",\"detail\":\"   \"}").andExpect(status().isCreated());
        assertThat(reports.findAll()).singleElement().satisfies(r -> assertThat(r.getDetail()).isNull());
        report(other, "{\"reason\":\"OTHER\",\"detail\":" + quote("가".repeat(501)) + "}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REPORT"));
        report(other, "{\"reason\":\"OTHER\",\"detail\":" + quote("😀".repeat(500)) + "}").andExpect(status().isCreated());
    }

    @Test void unknownOrMissingReasonIsRefused() throws Exception {
        report(student, "{\"reason\":\"SPAM\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REPORT"));
        report(student, "{\"reason\":\"other\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REPORT"));
        report(student, "{\"detail\":\"사유 없음\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REPORT"));
        report(student, "{\"reason\":1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(reports.count()).isZero();
    }

    @Test void authorCannotReportOwnPostAndRemovedPostsAreNotFound() throws Exception {
        report(author, "{\"reason\":\"OTHER\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("SELF_REPORT_NOT_ALLOWED"));
        mvc.perform(get("/api/posts/" + post).with(as(author))).andExpect(jsonPath("$.data.viewer.reported").value(false));
        remove();
        report(student, "{\"reason\":\"OTHER\"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
        assertThat(reports.count()).isZero();
    }

    @Test void cancelledAndFinishedPostsCanStillBeReported() throws Exception {
        state(PostStatus.CANCELLED);
        report(student, "{\"reason\":\"FALSE_RECRUITMENT\"}").andExpect(status().isCreated());
        clock.set(TIME.plusSeconds(3600));
        report(other, "{\"reason\":\"ABUSE\"}").andExpect(status().isCreated());
    }

    @Test void simultaneousReportsBySameUserKeepOne() throws Exception {
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) results.add(pool.submit(() -> {
                start.await();
                return report(student, "{\"reason\":\"OTHER\"}").andReturn().getResponse().getStatus();
            }));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        } finally { pool.shutdownNow(); }
        assertThat(reports.count()).isEqualTo(1);
    }
}
