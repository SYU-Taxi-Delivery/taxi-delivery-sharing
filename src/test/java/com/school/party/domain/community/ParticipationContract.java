package com.school.party.domain.community;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.repository.UserRepository;


import tools.jackson.databind.ObjectMapper;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.school.party.domain.community.api.ErrorCode.*;

/** The same contract runs against H2, an external InnoDB database, or Testcontainers MySQL. */
public abstract class ParticipationContract {
    protected static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    protected static final Instant REFERENCE = Instant.parse("2026-10-06T09:00:00Z");
    @Autowired protected ParticipationService service;
    @Autowired protected PostLifecycleService lifecycle;
    @Autowired protected PostRepository posts;
    @Autowired protected ParticipationRepository participations;
    @Autowired protected UserRepository users;
    @Autowired protected MutableClock clock;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactionManager;
    protected User author, student, other, admin;
    protected Post post;

    private static User fixtureUser(String name, Role role) {
        String key = java.util.UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syu.ac.kr")
            .name(name).role(role).status(com.school.party.domain.user.enums.UserStatus.ACTIVE).build();
    }
    @BeforeEach void arrange() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); clock.set(NOW);
        author = users.save(fixtureUser("모집작성자", Role.STUDENT));
        student = users.save(fixtureUser("학생A", Role.STUDENT));
        other = users.save(fixtureUser("학생B", Role.STUDENT));
        admin = users.save(fixtureUser("관리자", Role.ADMIN));
        post = posts.save(Post.taxi(author, 4, REFERENCE, NOW));
    }
    protected long id() { return post.getId(); }
    protected long actor() { return student.getId(); }
    protected long active(long postId) { return participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED); }
    protected Post twoSeats() { return posts.save(Post.taxi(author, 2, REFERENCE.plusSeconds(60), NOW)); }
    protected org.springframework.test.web.servlet.request.RequestPostProcessor as(User who) { return user(new CampusUser(who.getId(), who.getName(), "unused", who.getRole())); }
    protected void rejects(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test void JOIN001_initialJoinCountsAuthorExactlyOnce() {
        assertThat(service.mine(id(), actor()).data().post().currentMemberCount()).isEqualTo(1);
        var result = service.join(id(), actor());
        assertThat(result.data().action()).isEqualTo("INITIAL_JOIN");
        assertThat(result.data().post().currentMemberCount()).isEqualTo(2);
        assertThat(result.data().viewer().canJoin()).isFalse();
        assertThat(result.data().viewer().canCancel()).isTrue();
        assertThat(participations.findByPost_IdAndUser_Id(id(), author.getId())).isEmpty();
    }
    @Test void JOIN003_rejoinReusesRecordAndOriginalCreatedAt() {
        var initial = service.join(id(), actor()).data().participation();
        clock.set(NOW.plusSeconds(60)); service.cancel(id(), actor());
        clock.set(NOW.plusSeconds(120)); var rejoined = service.join(id(), actor()).data();
        assertThat(rejoined.action()).isEqualTo("REJOIN");
        assertThat(rejoined.participation().participationId()).isEqualTo(initial.participationId());
        assertThat(rejoined.participation().createdAt()).isEqualTo(initial.createdAt());
        assertThat(rejoined.participation().updatedAt().toInstant()).isEqualTo(NOW.plusSeconds(120));
        assertThat(rejoined.participation().cancelledAt()).isNull();
        assertThat(participations.count()).isEqualTo(1);
    }
    @Test void JOIN004_authorCannotJoinOrCancelParticipation() {
        rejects(SELF_PARTICIPATION_NOT_ALLOWED, () -> service.join(id(), author.getId()));
        rejects(AUTHOR_CANNOT_CANCEL_PARTICIPATION, () -> service.cancel(id(), author.getId()));
        assertThat(active(id())).isZero();
        assertThat(service.mine(id(), author.getId()).data().viewer().canJoin()).isFalse();
    }
    @Test void JOIN003_duplicateJoinDoesNotChangeCount() {
        service.join(id(), actor());
        rejects(ALREADY_JOINED, () -> service.join(id(), actor()));
        assertThat(active(id())).isEqualTo(1);
    }
    @Test void JOIN007_lastSeatTransitionsToFull() {
        Post small = twoSeats(); var joined = service.join(small.getId(), actor());
        assertThat(joined.data().post().postStatus()).isEqualTo(PostStatus.FULL);
        assertThat(joined.data().post().currentMemberCount()).isEqualTo(2);
        rejects(POST_FULL, () -> service.join(small.getId(), other.getId()));
        assertThat(active(small.getId())).isEqualTo(1);
    }
    @Test void JOIN005_exactDeadlineRejectsNewJoin() {
        clock.set(REFERENCE.minusSeconds(600));
        rejects(JOIN_TIME_CLOSED, () -> service.join(id(), actor()));
        assertThat(active(id())).isZero();
        assertThat(service.mine(id(), actor()).data().post().postStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(service.mine(id(), actor()).data().post().joinable()).isFalse();
    }
    @Test void JOIN005_oneSecondBeforeDeadlineAllowsJoin() {
        clock.set(REFERENCE.minusSeconds(601));
        assertThat(service.join(id(), actor()).data().participation().status()).isEqualTo(ParticipationStatus.JOINED);
    }
    @Test void JOIN006_exactDeadlineRejectsCancelWithoutMutation() {
        service.join(id(), actor()); clock.set(REFERENCE.minusSeconds(600));
        rejects(CANCEL_TIME_CLOSED, () -> service.cancel(id(), actor()));
        assertThat(active(id())).isEqualTo(1);
        assertThat(participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow().getCancelledAt()).isNull();
    }
    @Test void JOIN006_oneSecondBeforeDeadlineAllowsCancel() {
        service.join(id(), actor()); clock.set(REFERENCE.minusSeconds(601));
        assertThat(service.cancel(id(), actor()).data().participation().status()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(active(id())).isZero();
    }
    @Test void JOIN008_cancelFromFullAutomaticallyReopens() {
        Post small = twoSeats(); service.join(small.getId(), actor());
        var cancelled = service.cancel(small.getId(), actor()).data();
        assertThat(cancelled.post().postStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(cancelled.post().currentMemberCount()).isEqualTo(1);
        assertThat(cancelled.viewer().canJoin()).isTrue();
    }
    @Test void JOIN006_cancelFromClosedPreservesClosed() {
        service.join(id(), actor()); lifecycle.close(id(), author.getId());
        var result = service.cancel(id(), actor());
        assertThat(result.data().post().postStatus()).isEqualTo(PostStatus.CLOSED);
        assertThat(result.data().post().joinable()).isFalse();
    }
    @Test void POST005_fullCanBeManuallyClosed() {
        Post small = twoSeats(); service.join(small.getId(), actor()); lifecycle.close(small.getId(), author.getId());
        assertThat(service.mine(small.getId(), actor()).data().post().postStatus()).isEqualTo(PostStatus.CLOSED);
        rejects(POST_CLOSED, () -> service.join(small.getId(), other.getId()));
    }
    @Test void JOIN006_repeatedCancelPreservesTimestampAndCount() {
        service.join(id(), actor()); clock.set(NOW.plusSeconds(30));
        var cancelled = service.cancel(id(), actor()).data().participation(); clock.set(NOW.plusSeconds(60));
        rejects(ALREADY_CANCELLED, () -> service.cancel(id(), actor()));
        var p = participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow();
        assertThat(p.getCancelledAt()).isEqualTo(cancelled.cancelledAt().toInstant());
        assertThat(active(id())).isZero();
    }
    @Test void JOIN006_nonParticipantCannotCancel() { rejects(PARTICIPATION_NOT_FOUND, () -> service.cancel(id(), actor())); }
    @Test void POST008_expiredStoredOpenIsFinishedWithoutChangingJoinedHistory() {
        service.join(id(), actor()); clock.set(REFERENCE);
        rejects(POST_FINISHED, () -> service.join(id(), other.getId()));
        rejects(POST_FINISHED, () -> service.cancel(id(), actor()));
        assertThat(service.mine(id(), actor()).data().post().postStatus()).isEqualTo(PostStatus.FINISHED);
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(service.members(id(), actor()).data().participants()).hasSize(1);
    }
    @Test void DISC004_ownerCanSeeEmptyListButNonParticipantCannotSeeNames() {
        assertThat(service.members(id(), author.getId()).data().participants()).isEmpty();
        rejects(PARTICIPANT_LIST_FORBIDDEN, () -> service.members(id(), actor()));
    }
    @Test void DISC004_cancelledParticipantLosesListPermissionImmediately() {
        service.join(id(), actor()); service.join(id(), other.getId()); service.cancel(id(), actor());
        rejects(PARTICIPANT_LIST_FORBIDDEN, () -> service.members(id(), actor()));
        assertThat(service.members(id(), other.getId()).data().participants()).hasSize(1);
    }
    @Test void POST006_recruitmentCancellationCancelsEveryActiveMember() {
        service.join(id(), actor()); service.join(id(), other.getId()); lifecycle.cancelRecruitment(id(), author.getId());
        assertThat(active(id())).isZero();
        assertThat(service.mine(id(), actor()).data().post().postStatus()).isEqualTo(PostStatus.CANCELLED);
        assertThat(service.mine(id(), actor()).data().participation().cancelledAt()).isNotNull();
        rejects(POST_CANCELLED, () -> service.join(id(), other.getId()));
        rejects(POST_CANCELLED, () -> service.cancel(id(), actor()));
    }
    @Test void ADMIN003_removalCancelsActiveMembersAndReturnsOnlyTombstone() throws Exception {
        service.join(id(), actor()); lifecycle.remove(id(), admin.getId());
        assertThat(active(id())).isZero();
        rejects(POST_NOT_FOUND, () -> service.mine(id(), actor()));
        String json = mapper.writeValueAsString(service.history(actor(), 0, 20, null).data());
        assertThat(json).contains("REMOVED", "관리자에 의해 삭제된 모집").doesNotContain("title", "referenceTime", "displayName", "communicationUrl");
    }
    @Test void ADMIN003_removalOfFinishedPostPreservesJoinedHistory() {
        service.join(id(), actor()); clock.set(REFERENCE); lifecycle.remove(id(), admin.getId());
        assertThat(active(id())).isEqualTo(1);
        assertThat(service.history(actor(), 0, 20, null).data().items().get(0).participationStatus()).isEqualTo(ParticipationStatus.JOINED);
    }
    @Test void AUTH004_normalAdminDoesNotBypassMemberListPermission() { rejects(PARTICIPANT_LIST_FORBIDDEN, () -> service.members(id(), admin.getId())); }
    @Test void AUTH004_nonAdminCannotRemoveAndOtherUserCannotCloseRecruitment() {
        rejects(FORBIDDEN_OPERATION, () -> lifecycle.remove(id(), actor()));
        rejects(FORBIDDEN_OPERATION, () -> lifecycle.close(id(), actor()));
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
    }
    @Test void MY002_historyNeverMixesOtherUsersAndFiltersCurrentStatus() {
        service.join(id(), actor()); service.join(id(), other.getId()); service.cancel(id(), actor());
        assertThat(service.history(actor(), 0, 20, null).data().totalElements()).isEqualTo(1);
        assertThat(service.history(actor(), 0, 20, "JOINED").data().items()).isEmpty();
        assertThat(service.history(actor(), 0, 20, "CANCELLED").data().items()).hasSize(1);
        rejects(INVALID_QUERY_PARAMETER, () -> service.history(actor(), -1, 20, null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.history(actor(), 0, 51, null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.history(actor(), 0, 20, "NONE"));
    }
    @Test void JOIN003_differentRecruitmentsAreAllowed() {
        Post second = posts.save(Post.delivery(author, 10, REFERENCE.plusSeconds(60), NOW));
        service.join(id(), actor()); service.join(second.getId(), actor());
        assertThat(service.history(actor(), 0, 20, null).data().totalElements()).isEqualTo(2);
    }
    @Test void DELIVERY_capacityTenIncludesAuthorAndPreventsEleventhMember() {
        Post delivery = posts.save(Post.delivery(author, 10, REFERENCE.plusSeconds(60), NOW));
        for (int i = 0; i < 9; i++) {
            User u = users.save(fixtureUser("배달참가자" + i, Role.STUDENT)); service.join(delivery.getId(), u.getId());
        }
        assertThat(service.mine(delivery.getId(), actor()).data().post().currentMemberCount()).isEqualTo(10);
        rejects(POST_FULL, () -> service.join(delivery.getId(), actor()));
    }
    @Test void JOIN002_concurrentLastSeatNeverExceedsCapacity() throws Exception {
        Post small = twoSeats(); List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(users.save(fixtureUser("경쟁참가자" + i, Role.STUDENT)).getId());
        List<String> outcomes = compete(ids.stream().<Callable<String>>map(userId -> () -> attempt(() -> service.join(small.getId(), userId))).toList());
        assertThat(Collections.frequency(outcomes, "OK")).isEqualTo(1);
        assertThat(outcomes).allMatch(v -> v.equals("OK") || v.equals("POST_FULL"));
        assertThat(active(small.getId())).isEqualTo(1);
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
    }
    @Test void JOIN003_concurrentDuplicateHasOneLogicalParticipation() throws Exception {
        var outcomes = compete(List.of(() -> attempt(() -> service.join(id(), actor())), () -> attempt(() -> service.join(id(), actor()))));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "ALREADY_JOINED");
        assertThat(active(id())).isEqualTo(1);
        assertThat(participations.count()).isEqualTo(1);
    }
    @Test void POST006_joinRacingRecruitmentCancelLeavesNoJoinedMembers() throws Exception {
        List<String> outcomes = compete(List.of(() -> attempt(() -> service.join(id(), actor())), () -> attempt(() -> lifecycle.cancelRecruitment(id(), author.getId()))));
        assertThat(outcomes).contains("OK").allMatch(v -> v.equals("OK") || v.equals("POST_CANCELLED"));
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.CANCELLED);
        assertThat(active(id())).isZero();
    }
    @Test void ADMIN003_joinRacingRemovalLeavesNoJoinedMembers() throws Exception {
        List<String> outcomes = compete(List.of(() -> attempt(() -> service.join(id(), actor())), () -> attempt(() -> lifecycle.remove(id(), admin.getId()))));
        assertThat(outcomes).contains("OK").allMatch(v -> v.equals("OK") || v.equals("POST_NOT_FOUND"));
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(active(id())).isZero();
    }
    @Test void JOIN005_waitingForPostLockRechecksDeadlineAfterLock() throws Exception {
        clock.set(REFERENCE.minusSeconds(601));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                posts.findByIdForUpdate(id()).orElseThrow();
                futures.add(executor.submit(() -> { started.countDown(); return attempt(() -> service.join(id(), actor())); }));
                try { assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                assertThat(futures.get(0).isDone()).isFalse();
                clock.set(REFERENCE.minusSeconds(600));
            });
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)).isEqualTo("JOIN_TIME_CLOSED");
            assertThat(active(id())).isZero();
        } finally { executor.shutdownNow(); }
    }
    @Test void NFR_TXN_constraintFailureRollsBackParticipationAndFullTransition() throws Exception {
        Post small = twoSeats();
        jdbc.execute("ALTER TABLE participation ADD CONSTRAINT ck_test_force_failure CHECK (user_id <> " + actor() + ")");
        try {
            assertThatThrownBy(() -> service.join(small.getId(), actor())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(active(small.getId())).isZero();
            assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        } finally {
            String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            jdbc.execute("ALTER TABLE participation DROP " + (product.equalsIgnoreCase("MySQL") ? "CHECK " : "CONSTRAINT ") + "ck_test_force_failure");
        }
    }
    @Test void AUTH004_missingDatabaseUserCannotAct() { rejects(AUTHENTICATION_REQUIRED, () -> service.join(id(), Long.MAX_VALUE)); }
    @Test void missingPostIsNotFound() { rejects(POST_NOT_FOUND, () -> service.join(Long.MAX_VALUE, actor())); }

    @Test void TXN_waitingJoinSeesCommittedFullStateAtLastSeat() throws Exception {
        Post small = twoSeats();
        assertThat(ordered(small.getId(), () -> service.join(small.getId(), actor()),
            () -> service.join(small.getId(), other.getId()), () -> {}, false)).isEqualTo("POST_FULL");
        assertThat(active(small.getId())).isEqualTo(1);
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
        assertThat(participations.findByPost_IdAndUser_Id(small.getId(), other.getId())).isEmpty();
    }
    @Test void TXN_waitingJoinRecountsMembersWhenStatusRemainsOpen() throws Exception {
        Post threeSeats = posts.save(Post.taxi(author, 3, REFERENCE.plusSeconds(60), NOW));
        assertThat(ordered(threeSeats.getId(), () -> service.join(threeSeats.getId(), actor()),
            () -> service.join(threeSeats.getId(), other.getId()), () -> {}, false)).isEqualTo("OK");
        assertThat(active(threeSeats.getId())).isEqualTo(2);
        assertThat(posts.findById(threeSeats.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void TXN_joinAndRecruitmentCancelAreConsistentInBothOrders(boolean joinFirst) throws Exception {
        service.join(id(), other.getId());
        Runnable join = () -> service.join(id(), actor());
        Runnable cancel = () -> lifecycle.cancelRecruitment(id(), author.getId());
        assertThat(ordered(id(), joinFirst ? join : cancel, joinFirst ? cancel : join, () -> {}, false))
            .isEqualTo(joinFirst ? "OK" : "POST_CANCELLED");
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.CANCELLED);
        assertThat(active(id())).isZero();
        assertThat(participations.findMembers(id(), ParticipationStatus.CANCELLED))
            .hasSize(joinFirst ? 2 : 1).allSatisfy(p -> assertThat(p.getCancelledAt()).isEqualTo(NOW));
        if (!joinFirst) assertThat(participations.findByPost_IdAndUser_Id(id(), actor())).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void TXN_joinAndAdminRemovalAreConsistentInBothOrders(boolean joinFirst) throws Exception {
        service.join(id(), other.getId());
        Runnable join = () -> service.join(id(), actor());
        Runnable remove = () -> lifecycle.remove(id(), admin.getId());
        assertThat(ordered(id(), joinFirst ? join : remove, joinFirst ? remove : join, () -> {}, false))
            .isEqualTo(joinFirst ? "OK" : "POST_NOT_FOUND");
        Post saved = posts.findById(id()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(saved.getDeletedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select deleted_by from post where id = ?", Long.class, id())).isEqualTo(admin.getId());
        assertThat(active(id())).isZero();
        assertThat(participations.findMembers(id(), ParticipationStatus.CANCELLED)).hasSize(joinFirst ? 2 : 1);
        if (!joinFirst) assertThat(participations.findByPost_IdAndUser_Id(id(), actor())).isEmpty();
    }
    @Test void TXN_waitingParticipantCancelPreservesCommittedClosedStatus() throws Exception {
        Post small = twoSeats(); service.join(small.getId(), actor());
        assertThat(ordered(small.getId(), () -> lifecycle.close(small.getId(), author.getId()),
            () -> service.cancel(small.getId(), actor()), () -> {}, false)).isEqualTo("OK");
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.CLOSED);
        assertThat(active(small.getId())).isZero();
        assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow().getCancelledAt()).isEqualTo(NOW);
    }
    @ParameterizedTest @ValueSource(strings = {"close", "cancelRecruitment"})
    void TXN_lifecycleRechecksExpiryAfterWaiting(String action) throws Exception {
        service.join(id(), actor());
        Runnable mutation = action.equals("close") ? () -> lifecycle.close(id(), author.getId())
            : () -> lifecycle.cancelRecruitment(id(), author.getId());
        assertThat(ordered(id(), () -> {}, mutation, () -> clock.set(REFERENCE), false)).isEqualTo("POST_FINISHED");
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(active(id())).isEqualTo(1);
    }
    @Test void TXN_participantCancelRechecksDeadlineAfterWaiting() throws Exception {
        Post small = twoSeats(); service.join(small.getId(), actor());
        assertThat(ordered(small.getId(), () -> {}, () -> service.cancel(small.getId(), actor()),
            () -> clock.set(small.getReferenceTime().minusSeconds(600)), false)).isEqualTo("CANCEL_TIME_CLOSED");
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
        assertThat(active(small.getId())).isEqualTo(1);
        assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow().getCancelledAt()).isNull();
    }
    @Test void TXN_adminRemovalRechecksExpiryAndPreservesFinishedHistoryAfterWaiting() throws Exception {
        service.join(id(), actor());
        assertThat(ordered(id(), () -> {}, () -> lifecycle.remove(id(), admin.getId()),
            () -> clock.set(REFERENCE), false)).isEqualTo("OK");
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.REMOVED);
        assertThat(active(id())).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"cancelRecruitment", "remove"})
    void TXN_preloadedPostIsRevalidatedAfterLockInAnExistingTransaction(String action) throws Exception {
        Runnable terminal = action.equals("remove") ? () -> lifecycle.remove(id(), admin.getId())
            : () -> lifecycle.cancelRecruitment(id(), author.getId());
        assertThat(ordered(id(), terminal, () -> service.join(id(), actor()), () -> {}, true))
            .isEqualTo(action.equals("remove") ? "POST_NOT_FOUND" : "POST_CANCELLED");
        assertThat(active(id())).isZero();
        assertThat(participations.findByPost_IdAndUser_Id(id(), actor())).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void TXN_preloadedParticipationIsRevalidatedAfterLock(boolean rejoinAfterCancel) throws Exception {
        service.join(id(), actor());
        long participationId = participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow().getId();
        if (!rejoinAfterCancel) service.cancel(id(), actor());
        Runnable join = () -> service.join(id(), actor());
        Runnable cancel = () -> service.cancel(id(), actor());
        assertThat(ordered(id(), rejoinAfterCancel ? cancel : join, rejoinAfterCancel ? join : cancel, () -> {}, true)).isEqualTo("OK");
        Participation saved = participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow();
        assertThat(saved.getId()).isEqualTo(participationId);
        assertThat(saved.getStatus()).isEqualTo(rejoinAfterCancel ? ParticipationStatus.JOINED : ParticipationStatus.CANCELLED);
        assertThat(active(id())).isEqualTo(rejoinAfterCancel ? 1 : 0);
    }
    @ParameterizedTest @ValueSource(strings = {"join", "cancel", "cancelRecruitment", "remove"})
    void TXN_databaseFailureAfterBothTablesWereFlushedRollsBackBoth(String action) {
        Post small = twoSeats();
        boolean joining = action.equals("join");
        if (!joining) service.join(small.getId(), actor());
        PostStatus originalStatus = joining ? PostStatus.OPEN : PostStatus.FULL;
        assertThatThrownBy(() -> readCommittedTransaction().executeWithoutResult(tx -> {
            switch (action) {
                case "join" -> service.join(small.getId(), actor());
                case "cancel" -> service.cancel(small.getId(), actor());
                case "cancelRecruitment" -> lifecycle.cancelRecruitment(small.getId(), author.getId());
                case "remove" -> lifecycle.remove(small.getId(), admin.getId());
                default -> throw new AssertionError(action);
            }
            posts.flush();
            String expected = switch (action) { case "join" -> "FULL"; case "cancel" -> "OPEN";
                case "cancelRecruitment" -> "CANCELLED"; default -> "REMOVED"; };
            assertThat(jdbc.queryForObject("select status from post where id = ?", String.class, small.getId())).isEqualTo(expected);
            assertThat(jdbc.queryForObject("select status from participation where post_id = ? and user_id = ?",
                String.class, small.getId(), actor())).isEqualTo(joining ? "JOINED" : "CANCELLED");
            // Fail a real DB constraint only AFTER SQL changes to both tables are visible in this transaction.
            jdbc.update("update post set capacity = 0 where id = ?", small.getId());
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Post saved = posts.findById(small.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(originalStatus);
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(jdbc.queryForObject("select deleted_by from post where id = ?", Long.class, small.getId())).isNull();
        if (joining) assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor())).isEmpty();
        else {
            Participation restored = participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow();
            assertThat(restored.getStatus()).isEqualTo(ParticipationStatus.JOINED);
            assertThat(restored.getCancelledAt()).isNull();
            assertThat(restored.getUpdatedAt()).isEqualTo(NOW);
        }
    }
    @Test void TXN_databaseUniqueConstraintRejectsDuplicateParticipationOutsideService() {
        service.join(id(), actor());
        assertThatThrownBy(() -> participations.saveAndFlush(new Participation(post, student, NOW)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(active(id())).isEqualTo(1);
    }
    @Test void TXN_failedRejoinRestoresCancelledRecordAndOpenPost() {
        Post small = twoSeats(); service.join(small.getId(), actor()); service.cancel(small.getId(), actor());
        Participation original = participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow();
        assertThatThrownBy(() -> readCommittedTransaction().executeWithoutResult(tx -> {
            service.join(small.getId(), actor()); posts.flush();
            assertThat(jdbc.queryForObject("select status from post where id = ?", String.class, small.getId())).isEqualTo("FULL");
            assertThat(jdbc.queryForObject("select status from participation where id = ?", String.class, original.getId())).isEqualTo("JOINED");
            jdbc.update("update post set capacity = 0 where id = ?", small.getId());
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Participation restored = participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow();
        assertThat(restored.getId()).isEqualTo(original.getId());
        assertThat(restored.getStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(restored.getCancelledAt()).isEqualTo(original.getCancelledAt());
        assertThat(restored.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(active(small.getId())).isZero();
    }
    @Test void TXN_databaseForeignKeyAndStateCheckRejectInvalidDirectWrites() {
        service.join(id(), actor());
        assertThatThrownBy(() -> jdbc.update("update participation set post_id = ? where post_id = ? and user_id = ?", Long.MAX_VALUE, id(), actor()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update participation set status = 'CANCELLED', cancelled_at = null where post_id = ? and user_id = ?", id(), actor()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(active(id())).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void TXN_incompatibleOuterTransactionIsRejectedBeforeMutation(boolean readOnly) {
        TransactionTemplate transaction = readCommittedTransaction();
        if (readOnly) transaction.setReadOnly(true);
        else transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> service.join(id(), actor())))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(active(id())).isZero();
        assertThat(posts.findById(id()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
    }

    private TransactionTemplate readCommittedTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    private String ordered(long postId, Runnable firstAction, Runnable waitingAction, Runnable beforeRelease, boolean preloadPost) throws Exception {
        CountDownLatch prepared = new CountDownLatch(1), held = new CountDownLatch(1), started = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> waiting = executor.submit(() -> attempt(() -> readCommittedTransaction().executeWithoutResult(tx -> {
                if (preloadPost) {
                    posts.findById(postId).orElseThrow();
                    participations.findByPost_IdAndUser_Id(postId, actor());
                }
                prepared.countDown(); awaitSignal(held); started.countDown(); waitingAction.run();
            })));
            assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> first = executor.submit(() -> readCommittedTransaction().executeWithoutResult(tx -> {
                posts.findByIdForUpdate(postId).orElseThrow(); firstAction.run(); posts.flush();
                held.countDown(); awaitSignal(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            verifyBlockedOnPost(waiting);
            beforeRelease.run(); release.countDown();
            first.get(10, TimeUnit.SECONDS);
            return waiting.get(10, TimeUnit.SECONDS);
        } finally { held.countDown(); release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
    private void verifyBlockedOnPost(Future<?> waiting) throws Exception {
        DatabaseLockAssertions.awaitPrimaryRowWait(jdbc, "post", waiting);
    }
    private static void awaitSignal(CountDownLatch signal) {
        try { if (!signal.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test transaction coordination"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    @Test void HTTP_initialJoinIs201WithLocationAndNoSensitiveFields() throws Exception {
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/posts/" + id() + "/participants/me"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.participation.participationId").isString())
            .andExpect(jsonPath("$.data.post.currentMemberCount").value(2))
            .andExpect(jsonPath("$.data.viewer.canJoin").value(false))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
            .andExpect(jsonPath("$.meta.evaluatedAt").value("2026-10-06T17:00:00+09:00"))
            .andExpect(jsonPath("$.data.communicationUrl").doesNotExist());
    }
    @Test void HTTP_cancelAndRejoinAre200AndRejoinKeepsId() throws Exception {
        var first = service.join(id(), actor()).data().participation();
        mvc.perform(delete("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.participation.status").value("CANCELLED"));
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("REJOIN"))
            .andExpect(jsonPath("$.data.participation.participationId").value(first.participationId()));
    }

    @Test void REV_HTTP_joinCancelRejoinMatchesDatabaseAndFilteredHistory() throws Exception {
        var first = mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.action").value("INITIAL_JOIN"))
            .andExpect(jsonPath("$.data.post.currentMemberCount").value(2)).andReturn();
        var initial = mapper.readTree(first.getResponse().getContentAsString()).path("data").path("participation");
        String recordId = initial.path("participationId").asText(), createdAt = initial.path("createdAt").asText();
        clock.set(NOW.plusSeconds(30));
        mvc.perform(delete("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("CANCEL"))
            .andExpect(jsonPath("$.data.participation.status").value("CANCELLED"))
            .andExpect(jsonPath("$.data.participation.createdAt").value(createdAt))
            .andExpect(jsonPath("$.data.post.currentMemberCount").value(1));
        mvc.perform(get("/api/users/me/participations").param("status", "CANCELLED").with(as(student)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/users/me/participations").param("status", "JOINED").with(as(student)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        clock.set(NOW.plusSeconds(60));
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("REJOIN"))
            .andExpect(jsonPath("$.data.participation.participationId").value(recordId))
            .andExpect(jsonPath("$.data.participation.createdAt").value(createdAt))
            .andExpect(jsonPath("$.data.participation.updatedAt").value("2026-10-06T17:01:00+09:00"))
            .andExpect(jsonPath("$.data.participation.cancelledAt").isEmpty())
            .andExpect(jsonPath("$.data.post.currentMemberCount").value(2));
        var saved = participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow();
        assertThat(saved.getId().toString()).isEqualTo(recordId);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW); assertThat(saved.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(saved.getCancelledAt()).isNull(); assertThat(participations.count()).isEqualTo(1);
        mvc.perform(get("/api/users/me/participations").param("status", "CANCELLED").with(as(student)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/users/me/participations").param("status", "JOINED").with(as(student)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].participationId").value(recordId));
    }

    @ParameterizedTest @ValueSource(strings = {"join", "cancel", "mine", "members"})
    void REV_HTTP_missingRecruitmentReturns404WithoutMutation(String action) throws Exception {
        String path = "/api/posts/" + Long.MAX_VALUE + "/participants";
        var request = switch (action) {
            case "join" -> post(path);
            case "cancel" -> delete(path);
            case "mine" -> get(path + "/me");
            default -> get(path);
        };
        mvc.perform(request.with(as(student)).with(csrf())).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
            .andExpect(jsonPath("$.data").doesNotExist());
        assertThat(posts.count()).isEqualTo(1); assertThat(participations.count()).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {601, 600, 599})
    void REV_HTTP_cancelDeadlineBoundaryPreservesFullStateOnFailure(int remainingSeconds) throws Exception {
        Post small = twoSeats(); service.join(small.getId(), actor());
        clock.set(small.getReferenceTime().minusSeconds(remainingSeconds));
        var result = mvc.perform(delete("/api/posts/{id}/participants", small.getId()).with(as(student)).with(csrf())).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        var saved = participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow();
        if (remainingSeconds == 601) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.path("data").path("post").path("postStatus").asText()).isEqualTo("OPEN");
            assertThat(json.path("data").path("post").path("currentMemberCount").asInt()).isEqualTo(1);
            assertThat(saved.getStatus()).isEqualTo(ParticipationStatus.CANCELLED);
            assertThat(active(small.getId())).isZero();
        } else {
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.path("error").path("code").asText()).isEqualTo("CANCEL_TIME_CLOSED");
            assertThat(saved.getStatus()).isEqualTo(ParticipationStatus.JOINED);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW); assertThat(saved.getCancelledAt()).isNull();
            assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
            assertThat(active(small.getId())).isEqualTo(1);
        }
    }

    @Test void REV_TXN_concurrentCancelsHaveOneSuccessAndDoNotSubtractTwice() throws Exception {
        Post small = twoSeats(); service.join(small.getId(), actor()); clock.set(NOW.plusSeconds(30));
        var outcomes = compete(List.of(() -> attempt(() -> service.cancel(small.getId(), actor())),
            () -> attempt(() -> service.cancel(small.getId(), actor()))));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "ALREADY_CANCELLED");
        assertThat(active(small.getId())).isZero(); assertThat(participations.count()).isEqualTo(1);
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(service.mine(small.getId(), actor()).data().post().currentMemberCount()).isEqualTo(1);
    }

    @Test void REV_TXN_concurrentRejoinsReactivateOneExistingRecordOnly() throws Exception {
        var initial = service.join(id(), actor()).data().participation();
        clock.set(NOW.plusSeconds(30)); service.cancel(id(), actor()); clock.set(NOW.plusSeconds(60));
        var outcomes = compete(List.of(() -> attempt(() -> service.join(id(), actor())), () -> attempt(() -> service.join(id(), actor()))));
        assertThat(outcomes).containsExactlyInAnyOrder("OK", "ALREADY_JOINED");
        var saved = participations.findByPost_IdAndUser_Id(id(), actor()).orElseThrow();
        assertThat(saved.getId().toString()).isEqualTo(initial.participationId());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW); assertThat(saved.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(saved.getCancelledAt()).isNull(); assertThat(participations.count()).isEqualTo(1);
        assertThat(active(id())).isEqualTo(1);
    }

    @Test void REV_TXN_waitingJoinCanTakeTheSeatFreedByCommittedCancel() throws Exception {
        Post small = twoSeats(); service.join(small.getId(), actor());
        assertThat(ordered(small.getId(), () -> service.cancel(small.getId(), actor()),
            () -> service.join(small.getId(), other.getId()), () -> {}, true)).isEqualTo("OK");
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(PostStatus.FULL);
        assertThat(active(small.getId())).isEqualTo(1);
        assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.CANCELLED);
        assertThat(participations.findByPost_IdAndUser_Id(small.getId(), other.getId()).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.JOINED);
    }

    @ParameterizedTest @ValueSource(strings = {"join", "cancel"})
    void REV_HTTP_unsupportedAcceptMustNotCommitParticipationMutation(String action) throws Exception {
        Post small = twoSeats(); boolean cancelling = action.equals("cancel");
        if (cancelling) service.join(small.getId(), actor());
        var request = cancelling ? delete("/api/posts/{id}/participants", small.getId())
            : post("/api/posts/{id}/participants", small.getId());
        mvc.perform(request.with(as(student)).with(csrf()).accept(org.springframework.http.MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"));
        assertThat(active(small.getId())).isEqualTo(cancelling ? 1 : 0);
        assertThat(posts.findById(small.getId()).orElseThrow().getStatus()).isEqualTo(cancelling ? PostStatus.FULL : PostStatus.OPEN);
        if (cancelling) assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor()).orElseThrow().getCancelledAt()).isNull();
        else assertThat(participations.findByPost_IdAndUser_Id(small.getId(), actor())).isEmpty();
    }
    @Test void HTTP_unauthenticatedReadIs401() throws Exception {
        mvc.perform(get("/api/posts/{id}/participants/me", id())).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
    @Test void HTTP_unsupportedMethodIs405WithAllowHeader() throws Exception {
        mvc.perform(put("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()))
            .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
    @Test void HTTP_unknownApiPathIs404() throws Exception {
        mvc.perform(get("/api/unknown-participation-path").with(as(student)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("API_NOT_FOUND"));
    }
    @Test void HTTP_missingCsrfCannotMutate() throws Exception {
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student))).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        assertThat(active(id())).isZero();
    }
    @Test void HTTP_bodyUserIdCannotImpersonateAnyone() throws Exception {
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()).contentType("application/json").content("{\"userId\":\"" + other.getId() + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(active(id())).isZero();
    }
    @Test void HTTP_untrustedIdentityHeaderIsIgnored() throws Exception {
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf()).header("X-User-Id", other.getId()))
            .andExpect(status().isCreated());
        assertThat(participations.findByPost_IdAndUser_Id(id(), actor())).isPresent();
        assertThat(participations.findByPost_IdAndUser_Id(id(), other.getId())).isEmpty();
    }
    @Test void HTTP_genericUsernamePrincipalCannotPretendToBeDatabaseId() throws Exception {
        mvc.perform(post("/api/posts/{id}/participants", id()).with(user(Long.toString(actor()))).with(csrf()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(active(id())).isZero();
    }
    @Test void HTTP_invalidIdsReturn400Not500() throws Exception {
        for (String invalid : List.of("-1", "abc", "0", "9223372036854775808")) {
            mvc.perform(get("/api/posts/{id}/participants/me", invalid).with(as(student)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_POST_ID"));
        }
    }
    @Test void HTTP_invalidHistoryQueryIs400() throws Exception {
        mvc.perform(get("/api/users/me/participations").param("size", "51").with(as(student))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/me/participations").param("page", "abc").with(as(student))).andExpect(status().isBadRequest());
    }
    @Test void HTTP_noneParticipationReturns200AndNullRecord() throws Exception {
        mvc.perform(get("/api/posts/{id}/participants/me", id()).with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participation").isEmpty()).andExpect(jsonPath("$.data.viewer.myParticipationStatus").value("NONE"));
    }
    @Test void HTTP_memberPermissionDeniedDoesNotContainNames() throws Exception {
        mvc.perform(get("/api/posts/{id}/participants", id()).with(as(student))).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("PARTICIPANT_LIST_FORBIDDEN")).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void HTTP_historyTombstoneOmitsOriginalContent() throws Exception {
        service.join(id(), actor()); lifecycle.remove(id(), admin.getId());
        mvc.perform(get("/api/users/me/participations").with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].post.isRemoved").value(true))
            .andExpect(jsonPath("$.data.items[0].post.title").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].post.referenceTime").doesNotExist());
    }
    @Test void HTTP_timeErrorContainsDeadlineAndNoStateMutation() throws Exception {
        clock.set(REFERENCE.minusSeconds(600));
        mvc.perform(post("/api/posts/{id}/participants", id()).with(as(student)).with(csrf())).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("JOIN_TIME_CLOSED"))
            .andExpect(jsonPath("$.error.details.participationDeadline").value("2026-10-06T17:50:00+09:00"));
        assertThat(active(id())).isZero();
    }
    private String attempt(Runnable action) { try { action.run(); return "OK"; } catch (ApiException e) { return e.code().name(); } }
    private List<String> compete(List<Callable<String>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size()); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> pending = new ArrayList<>();
            for (Callable<String> action : actions) pending.add(executor.submit(() -> { start.await(); return action.call(); }));
            start.countDown(); List<String> results = new ArrayList<>();
            for (Future<String> f : pending) results.add(f.get(20, TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }
}
