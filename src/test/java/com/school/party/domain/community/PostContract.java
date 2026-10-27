package com.school.party.domain.community;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.repository.UserRepository;


import tools.jackson.databind.ObjectMapper;
import com.school.party.domain.community.api.*;
import com.school.party.domain.community.domain.*;
import com.school.party.domain.community.post.*;
import com.school.party.domain.community.repository.*;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.school.party.domain.community.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Behavior contract reusable against H2, a disposable InnoDB database, and MySQL Testcontainers. */
public abstract class PostContract {
    static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    static final Instant TIME = NOW.plusSeconds(3600);
    @Autowired PostService service;
    @Autowired ParticipationService participation;
    @Autowired PostLifecycleService lifecycle;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired ParticipationRepository participations;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    User author, student, admin;

    private static User fixtureUser(String name, Role role) {
        String key = java.util.UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syu.ac.kr")
            .name(name).role(role).status(com.school.party.domain.user.enums.UserStatus.ACTIVE).build();
    }
    @BeforeEach void arrange() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); clock.set(NOW);
        author = users.save(fixtureUser("작성자", Role.STUDENT));
        student = users.save(fixtureUser("참가자", Role.STUDENT));
        admin = users.save(fixtureUser("관리자", Role.ADMIN));
    }
    CreatePostRequest taxi(Instant at) {
        return new CreatePostRequest(PostType.TAXI, "택시 동승", "정문에서 만나요", 4,
            at.atOffset(ZoneOffset.ofHours(9)), "https://example.com/room", "석계역", "삼육대학교", null, null, null);
    }
    CreatePostRequest delivery(Instant at) {
        return new CreatePostRequest(PostType.DELIVERY, "공동 배달", "함께 주문해요", 10,
            at.atOffset(ZoneOffset.ofHours(9)), null, null, null, "음식점", "정문", "메뉴를 알려주세요");
    }
    long create() { return Long.parseLong(service.create(author.getId(), taxi(TIME)).data().postId()); }
    UpdatePostRequest edit(String json) throws Exception { return new UpdatePostRequest(mapper.readTree(json)); }
    org.springframework.test.web.servlet.request.RequestPostProcessor as(User user) {
        return user(new CampusUser(user.getId(), user.getName(), "unused", user.getRole()));
    }
    void rejects(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    void state(long id, PostStatus status) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> posts.findByIdForUpdate(id).orElseThrow().transitionTo(status, NOW));
    }

    @Test void creationUsesRequestAndCountsAuthorWithoutParticipation() {
        var result = service.create(author.getId(), taxi(TIME));
        var p = result.data();
        assertThat(p.title()).isEqualTo("택시 동승"); assertThat(p.content()).isEqualTo("정문에서 만나요");
        assertThat(p.departure()).isEqualTo("석계역"); assertThat(p.destination()).isEqualTo("삼육대학교");
        assertThat(p.postStatus()).isEqualTo(PostStatus.OPEN); assertThat(p.currentMemberCount()).isEqualTo(1);
        assertThat(p.joinable()).isTrue(); assertThat(p.viewer().canJoin()).isFalse();
        assertThat(p.viewer().canEditCore()).isTrue(); assertThat(p.communicationUrl()).isEqualTo("https://example.com/room");
        assertThat(participations.count()).isZero(); assertThat(p.referenceTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
    @Test void creationRejectsIncompatibleOuterTransactionBeforeWriting() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> service.create(author.getId(), taxi(TIME))))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(posts.count()).isZero();
    }
    @Test void deliveryPersistsItsFieldsOnly() {
        var p = service.create(author.getId(), delivery(TIME)).data();
        assertThat(p.restaurantName()).isEqualTo("음식점"); assertThat(p.pickupLocation()).isEqualTo("정문");
        assertThat(p.orderGuide()).isEqualTo("메뉴를 알려주세요"); assertThat(p.departure()).isNull(); assertThat(p.capacity()).isEqualTo(10);
    }
    @Test void exactTenMinutesCannotBeCreatedButNextMinuteCan() {
        rejects(POST_REFERENCE_TIME_TOO_EARLY, () -> service.create(author.getId(), taxi(NOW.plusSeconds(600))));
        assertThat(posts.count()).isZero();
        assertThat(service.create(author.getId(), taxi(NOW.plusSeconds(660))).data().postStatus()).isEqualTo(PostStatus.OPEN);
    }
    @Test void pastAndNonMinuteTimesAreRejected() {
        rejects(POST_REFERENCE_TIME_TOO_EARLY, () -> service.create(author.getId(), taxi(NOW.minusSeconds(60))));
        rejects(INVALID_POST_FIELD, () -> service.create(author.getId(), taxi(TIME.plusSeconds(1))));
        rejects(INVALID_POST_FIELD, () -> service.create(author.getId(), taxi(TIME.plusNanos(1))));
        assertThat(posts.count()).isZero();
    }
    @Test void sameAuthorCrossTypeConflictAndDifferentAuthorAllowed() {
        create(); rejects(POST_TIME_CONFLICT, () -> service.create(author.getId(), delivery(TIME)));
        assertThat(service.create(student.getId(), delivery(TIME)).data().type()).isEqualTo(PostType.DELIVERY);
        assertThat(posts.count()).isEqualTo(2);
    }
    @Test void fullAndClosedAreStillValidForDuplicateChecks() {
        long id = create(); state(id, PostStatus.FULL);
        rejects(POST_TIME_CONFLICT, () -> service.create(author.getId(), delivery(TIME)));
        lifecycle.close(id, author.getId());
        rejects(POST_TIME_CONFLICT, () -> service.create(author.getId(), delivery(TIME)));
    }
    @Test void cancelledRemovedAndStoredFinishedAreExcludedFromDuplicates() {
        long id = create(); lifecycle.cancelRecruitment(id, author.getId());
        long second = Long.parseLong(service.create(author.getId(), taxi(TIME)).data().postId());
        lifecycle.remove(second, admin.getId());
        long third = Long.parseLong(service.create(author.getId(), taxi(TIME)).data().postId());
        state(third, PostStatus.FINISHED);
        assertThat(service.create(author.getId(), delivery(TIME)).data().type()).isEqualTo(PostType.DELIVERY);
        assertThat(posts.count()).isEqualTo(4);
    }
    @Test void expiredStoredOpenIsExcludedFromConflictQuery() {
        create(); clock.set(TIME);
        assertThat(posts.countConflicts(author.getId(), 0, TIME, TIME)).isZero();
        assertThat(service.list(student.getId(), 0, 20, null, "FINISHED").data().totalElements()).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"TAXI,TAXI", "TAXI,DELIVERY", "DELIVERY,TAXI", "DELIVERY,DELIVERY"})
    void DUP_creationChecksBothTypesForSameAuthorAndTime(PostType occupiedType, PostType requestedType) {
        service.create(author.getId(), typedRequest(occupiedType, TIME));
        rejects(POST_TIME_CONFLICT, () -> service.create(author.getId(), typedRequest(requestedType, TIME)));
        assertThat(posts.count()).isEqualTo(1);
        assertThat(service.create(student.getId(), typedRequest(requestedType, TIME)).data().type()).isEqualTo(requestedType);
    }
    @ParameterizedTest @CsvSource({"TAXI,TAXI", "TAXI,DELIVERY", "DELIVERY,TAXI", "DELIVERY,DELIVERY"})
    void DUP_timeChangesCheckBothTypesAndPreserveRejectedPost(PostType occupiedType, PostType movingType) throws Exception {
        Instant target = TIME.plusSeconds(60);
        service.create(author.getId(), typedRequest(occupiedType, target));
        long moving = Long.parseLong(service.create(author.getId(), typedRequest(movingType, TIME)).data().postId());
        String originalTitle = posts.findById(moving).orElseThrow().getTitle();
        rejects(POST_TIME_CONFLICT, () -> service.update(moving, author.getId(), edit(mapper.writeValueAsString(Map.of(
            "referenceTime", target.atOffset(ZoneOffset.UTC).toString(), "title", "충돌한 수정")))));
        Post saved = posts.findById(moving).orElseThrow();
        assertThat(saved.getReferenceTime()).isEqualTo(TIME);
        assertThat(saved.getTitle()).isEqualTo(originalTitle);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(posts.countConflicts(author.getId(), 0, target, NOW)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(value = PostStatus.class, names = {"FULL", "CLOSED"})
    void DUP_timeChangeConflictsWithFullOrClosedRecruitment(PostStatus occupiedStatus) throws Exception {
        Instant target = TIME.plusSeconds(60);
        long occupied = Long.parseLong(service.create(author.getId(), taxi(target)).data().postId());
        state(occupied, occupiedStatus);
        long moving = Long.parseLong(service.create(author.getId(), delivery(TIME)).data().postId());
        rejects(POST_TIME_CONFLICT, () -> service.update(moving, author.getId(), edit(mapper.writeValueAsString(Map.of(
            "referenceTime", target.atOffset(ZoneOffset.UTC).toString())))));
        assertThat(posts.findById(moving).orElseThrow().getReferenceTime()).isEqualTo(TIME);
    }
    @ParameterizedTest @EnumSource(value = PostStatus.class, names = {"CANCELLED", "FINISHED", "REMOVED"})
    void DUP_timeChangeExcludesTerminalRecruitments(PostStatus terminalStatus) throws Exception {
        Instant target = TIME.plusSeconds(60);
        long occupied = Long.parseLong(service.create(author.getId(), taxi(target)).data().postId());
        if (terminalStatus == PostStatus.REMOVED) lifecycle.remove(occupied, admin.getId());
        else state(occupied, terminalStatus);
        long moving = Long.parseLong(service.create(author.getId(), delivery(TIME)).data().postId());
        var result = service.update(moving, author.getId(), edit(mapper.writeValueAsString(Map.of(
            "referenceTime", target.atOffset(ZoneOffset.UTC).toString())))).data();
        assertThat(result.referenceTime().toInstant()).isEqualTo(target);
        assertThat(posts.countConflicts(author.getId(), moving, target, NOW)).isZero();
        assertThat(posts.countConflicts(author.getId(), 0, target, NOW)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(value = PostStatus.class, names = {"OPEN", "FULL", "CLOSED"})
    void DUP_effectivelyFinishedRowsAreExcludedAtReferenceTimeWithoutStatusWrite(PostStatus storedStatus) {
        long id = create();
        if (storedStatus != PostStatus.OPEN) state(id, storedStatus);
        assertThat(posts.countConflicts(author.getId(), 0, TIME, TIME.minusSeconds(1))).isEqualTo(1);
        assertThat(posts.countConflicts(author.getId(), 0, TIME, TIME)).isZero();
        assertThat(posts.countConflicts(author.getId(), 0, TIME, TIME.plusSeconds(1))).isZero();
        clock.set(TIME);
        assertThat(service.get(id, student.getId()).data().postStatus()).isEqualTo(PostStatus.FINISHED);
        assertThat(posts.findById(id).orElseThrow().getStatus()).isEqualTo(storedStatus);
        // Exclusion from duplicate checks never permits a new recruitment at a past reference time.
        rejects(POST_REFERENCE_TIME_TOO_EARLY, () -> service.create(author.getId(), delivery(TIME)));
    }
    @Test void DUP_differentAuthorMayMoveToAnOccupiedReferenceTime() throws Exception {
        service.create(author.getId(), taxi(TIME));
        long moving = Long.parseLong(service.create(student.getId(), delivery(TIME.plusSeconds(60))).data().postId());
        assertThat(service.update(moving, student.getId(), edit(mapper.writeValueAsString(Map.of(
            "referenceTime", TIME.atOffset(ZoneOffset.UTC).toString())))).data().referenceTime().toInstant()).isEqualTo(TIME);
        assertThat(posts.countConflicts(author.getId(), 0, TIME, NOW)).isEqualTo(1);
        assertThat(posts.countConflicts(student.getId(), 0, TIME, NOW)).isEqualTo(1);
    }
    private CreatePostRequest typedRequest(PostType type, Instant at) { return type == PostType.TAXI ? taxi(at) : delivery(at); }
    @Test void authorCanEditCoreWhenNoJoinedMembers() throws Exception {
        long id = create(); Instant later = TIME.plusSeconds(60);
        var p = service.update(id, author.getId(), edit("{\"title\":\" 수정 제목 \",\"capacity\":3,\"destination\":\"후문\",\"referenceTime\":\"" + later.atOffset(ZoneOffset.UTC) + "\"}")).data();
        assertThat(p.title()).isEqualTo("수정 제목"); assertThat(p.content()).isEqualTo("정문에서 만나요");
        assertThat(p.capacity()).isEqualTo(3); assertThat(p.destination()).isEqualTo("후문");
        assertThat(p.referenceTime().toInstant()).isEqualTo(later);
    }
    @Test void joinedMembersLockAllCoreFieldsAndFailureRollsBack() throws Exception {
        long id = create(); participation.join(id, student.getId());
        for (String json : List.of("{\"capacity\":3}", "{\"departure\":\"정문\"}", "{\"destination\":\"후문\"}",
                "{\"referenceTime\":\"2026-10-12T18:01:00+09:00\"}")) {
            rejects(POST_CORE_FIELDS_LOCKED, () -> service.update(id, author.getId(), edit(json)));
        }
        rejects(POST_CORE_FIELDS_LOCKED, () -> service.update(id, author.getId(), edit("{\"title\":\"실패 변경\",\"capacity\":3}")));
        var p = service.get(id, author.getId()).data(); assertThat(p.title()).isEqualTo("택시 동승");
        assertThat(p.capacity()).isEqualTo(4); assertThat(p.currentMemberCount()).isEqualTo(2); assertThat(p.viewer().canEditCore()).isFalse();
    }
    @Test void nonCoreEditsWorkWithParticipantsAndFullState() throws Exception {
        long id = create(); participation.join(id, student.getId()); state(id, PostStatus.FULL);
        var p = service.update(id, author.getId(), edit("{\"title\":\"수정\",\"content\":\"새 안내\",\"communicationUrl\":null}")).data();
        assertThat(p.title()).isEqualTo("수정"); assertThat(p.content()).isEqualTo("새 안내");
        assertThat(p.communicationUrl()).isNull(); assertThat(p.postStatus()).isEqualTo(PostStatus.FULL);
    }
    @Test void cancelledParticipationDoesNotKeepCoreLocked() throws Exception {
        long id = create(); participation.join(id, student.getId()); participation.cancel(id, student.getId());
        assertThat(service.update(id, author.getId(), edit("{\"capacity\":2}")).data().capacity()).isEqualTo(2);
    }
    @Test void closedLocksCoreEvenWithoutParticipantsButAllowsTitle() throws Exception {
        long id = create(); lifecycle.close(id, author.getId());
        rejects(POST_CORE_FIELDS_LOCKED, () -> service.update(id, author.getId(), edit("{\"capacity\":2}")));
        assertThat(service.update(id, author.getId(), edit("{\"title\":\"마감 안내\"}")).data().postStatus()).isEqualTo(PostStatus.CLOSED);
    }
    @Test void deliveryCoreLockedButOrderGuideCanChange() throws Exception {
        long id = Long.parseLong(service.create(author.getId(), delivery(TIME)).data().postId());
        participation.join(id, student.getId());
        rejects(POST_CORE_FIELDS_LOCKED, () -> service.update(id, author.getId(), edit("{\"restaurantName\":\"다른 가게\"}")));
        rejects(POST_CORE_FIELDS_LOCKED, () -> service.update(id, author.getId(), edit("{\"pickupLocation\":\"후문\"}")));
        assertThat(service.update(id, author.getId(), edit("{\"orderGuide\":\"새 안내\"}")).data().orderGuide()).isEqualTo("새 안내");
    }
    @Test void timeEditRechecksDeadlineAndDuplicatesWithoutChangingExistingPost() throws Exception {
        long id = create(); service.create(author.getId(), delivery(TIME.plusSeconds(60)));
        rejects(POST_REFERENCE_TIME_TOO_EARLY, () -> service.update(id, author.getId(), edit("{\"referenceTime\":\"2026-10-12T17:10:00+09:00\"}")));
        rejects(POST_TIME_CONFLICT, () -> service.update(id, author.getId(), edit("{\"referenceTime\":\"2026-10-12T18:01:00+09:00\"}")));
        assertThat(service.get(id, author.getId()).data().referenceTime().toInstant()).isEqualTo(TIME);
    }
    @Test void unchangedCoreValuesAreAllowedAndDoNotReapplyCreationDeadline() throws Exception {
        long id = create(); participation.join(id, student.getId()); clock.set(TIME.minusSeconds(60));
        assertThat(service.update(id, author.getId(), edit("{\"capacity\":4,\"referenceTime\":\"2026-10-12T18:00:00+09:00\",\"title\":\"마감 직전 안내\"}")).data().title()).isEqualTo("마감 직전 안내");
    }
    @ParameterizedTest(name = "title-only PATCH with {0} seconds until existing reference time succeeds")
    @ValueSource(ints = {600, 599, 1})
    void titleOnlyUpdateDoesNotRecheckExistingReferenceTimeWithinTenMinutes(int secondsRemaining) throws Exception {
        long id = create();
        clock.set(TIME.minusSeconds(secondsRemaining));
        mvc.perform(patch("/api/posts/" + id).with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"출발 전 안내 수정\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("출발 전 안내 수정"));
        assertThat(posts.findById(id).orElseThrow().getReferenceTime()).isEqualTo(TIME);
    }
    @Test void otherNonCoreFieldsRemainEditableWithinTenMinutesOfClosedRecruitment() throws Exception {
        long id = Long.parseLong(service.create(author.getId(), delivery(TIME)).data().postId());
        participation.join(id, student.getId());
        lifecycle.close(id, author.getId());
        clock.set(TIME.minusSeconds(300));
        var detail = service.update(id, author.getId(), edit(
            "{\"content\":\"수령 안내 수정\",\"communicationUrl\":\"https://example.com/new-room\",\"orderGuide\":\"메뉴 안내 수정\"}")).data();
        assertThat(detail.content()).isEqualTo("수령 안내 수정");
        assertThat(detail.communicationUrl()).isEqualTo("https://example.com/new-room");
        assertThat(detail.orderGuide()).isEqualTo("메뉴 안내 수정");
        assertThat(detail.postStatus()).isEqualTo(PostStatus.CLOSED);
        assertThat(detail.referenceTime().toInstant()).isEqualTo(TIME);
    }
    @Test void sameReferenceInstantWithDifferentOffsetIsNotATimeChange() throws Exception {
        long id = create(); participation.join(id, student.getId());
        clock.set(TIME.minusSeconds(60));
        var detail = service.update(id, author.getId(), edit(mapper.writeValueAsString(Map.of(
            "referenceTime", TIME.atOffset(ZoneOffset.UTC).toString(), "title", "동일 시각 안내 수정")))).data();
        assertThat(detail.title()).isEqualTo("동일 시각 안내 수정");
        assertThat(detail.referenceTime().toInstant()).isEqualTo(TIME);
    }
    @ParameterizedTest(name = "changed reference time {0} seconds ahead must be strictly beyond ten minutes")
    @ValueSource(ints = {601, 600, 599})
    void changedReferenceTimeMustBeStrictlyMoreThanTenMinutesAhead(int secondsUntilNewReference) throws Exception {
        long id = create();
        Instant newReference = TIME.plusSeconds(60);
        clock.set(newReference.minusSeconds(secondsUntilNewReference));
        var request = patch("/api/posts/" + id).with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("referenceTime", newReference.atOffset(ZoneOffset.UTC).toString(),
                "title", "기준시각 변경 안내")));
        if (secondsUntilNewReference > 600) {
            mvc.perform(request).andExpect(status().isOk());
            Post saved = posts.findById(id).orElseThrow();
            assertThat(saved.getReferenceTime()).isEqualTo(newReference);
            assertThat(saved.getTitle()).isEqualTo("기준시각 변경 안내");
        } else {
            mvc.perform(request).andExpect(status().is(POST_REFERENCE_TIME_TOO_EARLY.status().value()))
                .andExpect(jsonPath("$.error.code").value("POST_REFERENCE_TIME_TOO_EARLY"));
            Post saved = posts.findById(id).orElseThrow();
            assertThat(saved.getReferenceTime()).isEqualTo(TIME);
            assertThat(saved.getTitle()).isEqualTo("택시 동승");
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }
    }
    @Test void authorOnlyAndNoChangesAfterCancelFinishOrRemove() throws Exception {
        long id = create();
        rejects(FORBIDDEN_OPERATION, () -> service.update(id, student.getId(), edit("{\"title\":\"위조\"}")));
        clock.set(TIME); rejects(POST_FINISHED, () -> service.update(id, author.getId(), edit("{\"title\":\"수정\"}")));
        clock.set(NOW); lifecycle.cancelRecruitment(id, author.getId());
        rejects(POST_CANCELLED, () -> service.update(id, author.getId(), edit("{\"title\":\"수정\"}")));
        lifecycle.remove(id, admin.getId());
        rejects(POST_NOT_FOUND, () -> service.update(id, author.getId(), edit("{\"title\":\"수정\"}")));
    }
    @Test void typeAndServerOwnedFieldsCannotBePatched() throws Exception {
        long id = create(); rejects(POST_TYPE_IMMUTABLE, () -> service.update(id, author.getId(), edit("{\"type\":\"DELIVERY\"}")));
        for (String field : List.of("authorId", "status", "postStatus", "currentMemberCount", "joinable", "deletedAt", "surprise"))
            rejects(INVALID_POST_FIELD, () -> service.update(id, author.getId(), edit("{\"" + field + "\":null}")));
        assertThat(posts.findById(id).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
    }
    @Test void omittedOptionalFieldRetainedExplicitNullClearsButRequiredNullRejected() throws Exception {
        long id = create();
        assertThat(service.update(id, author.getId(), edit("{\"title\":\"수정\"}")).data().communicationUrl()).isEqualTo("https://example.com/room");
        assertThat(service.update(id, author.getId(), edit("{\"communicationUrl\":null}")).data().communicationUrl()).isNull();
        for (String name : List.of("title", "content", "capacity", "referenceTime", "departure", "destination"))
            rejects(INVALID_POST_FIELD, () -> service.update(id, author.getId(), edit("{\"" + name + "\":null}")));
    }
    @Test void emptyNonObjectAndWrongTypesRejected() throws Exception {
        long id = create();
        for (String value : List.of("{}", "[]", "null", "\"text\""))
            rejects(INVALID_REQUEST, () -> service.update(id, author.getId(), edit(value)));
        for (String value : List.of("{\"capacity\":3.5}", "{\"capacity\":\"3\"}", "{\"title\":true}", "{\"referenceTime\":\"2026-10-12T18:00:00\"}"))
            rejects(INVALID_POST_FIELD, () -> service.update(id, author.getId(), edit(value)));
    }
    @Test void limitsBlankInputsAndHttpsAreValidatedOnServer() throws Exception {
        long id = create();
        for (String value : List.of("{\"title\":\" " + " " + "\"}", "{\"title\":\"" + "가".repeat(61) + "\"}",
            "{\"content\":\"" + "가".repeat(1001) + "\"}", "{\"departure\":\"" + "가".repeat(101) + "\"}",
            "{\"capacity\":1}", "{\"capacity\":5}", "{\"communicationUrl\":\"http://example.com\"}",
            "{\"communicationUrl\":\"https:///path\"}", "{\"restaurantName\":\"다른 유형\"}"))
            rejects(INVALID_POST_FIELD, () -> service.update(id, author.getId(), edit(value)));
        assertThat(service.update(id, author.getId(), edit("{\"title\":\"" + "가".repeat(60) + "\"}")).data().title()).hasSize(60);
    }
    @Test void detailOnlyDisclosesLinksToOwnerAndCurrentJoinedMembers() {
        long id = create();
        assertThat(service.get(id, student.getId()).data().communicationUrl()).isNull();
        assertThat(service.get(id, student.getId()).data().viewer().canJoin()).isTrue();
        participation.join(id, student.getId());
        var joined = service.get(id, student.getId()).data();
        assertThat(joined.communicationUrl()).isEqualTo("https://example.com/room");
        assertThat(joined.viewer().canJoin()).isFalse(); assertThat(joined.viewer().canCancel()).isTrue();
        participation.cancel(id, student.getId());
        assertThat(service.get(id, student.getId()).data().communicationUrl()).isNull();
    }
    @Test void finishedAndCancelledHideLinksEvenForOwnerOrFinalParticipant() {
        long id = create(); participation.join(id, student.getId()); clock.set(TIME);
        var p = service.get(id, student.getId()).data();
        assertThat(p.postStatus()).isEqualTo(PostStatus.FINISHED); assertThat(p.communicationUrl()).isNull();
        assertThat(p.viewer().canCancel()).isFalse(); assertThat(p.currentMemberCount()).isEqualTo(2);
        assertThat(posts.findById(id).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(service.get(id, author.getId()).data().communicationUrl()).isNull();
        clock.set(NOW); lifecycle.cancelRecruitment(id, author.getId()); clock.set(TIME);
        assertThat(service.get(id, author.getId()).data().postStatus()).isEqualTo(PostStatus.CANCELLED);
    }
    @Test void exactParticipationDeadlineRetainsOpenButJoinableFalse() {
        long id = create(); clock.set(TIME.minusSeconds(601));
        assertThat(service.get(id, student.getId()).data().joinable()).isTrue();
        clock.set(TIME.minusSeconds(600));
        var detail = service.get(id, student.getId()).data();
        assertThat(detail.joinable()).isFalse(); assertThat(detail.postStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(service.list(student.getId(), 0, 20, null, null).data().items().get(0).joinable()).isFalse();
    }
    @Test void defaultListExcludesClosedCancelledFinishedRemovedAndOrdersByTimeThenId() {
        long first = create();
        long second = Long.parseLong(service.create(student.getId(), delivery(TIME)).data().postId());
        long earlier = Long.parseLong(service.create(author.getId(), taxi(TIME.minusSeconds(60))).data().postId());
        long closed = Long.parseLong(service.create(author.getId(), taxi(TIME.plusSeconds(60))).data().postId()); lifecycle.close(closed, author.getId());
        long cancelled = Long.parseLong(service.create(author.getId(), taxi(TIME.plusSeconds(120))).data().postId()); lifecycle.cancelRecruitment(cancelled, author.getId());
        long finished = Long.parseLong(service.create(author.getId(), taxi(TIME.plusSeconds(180))).data().postId()); state(finished, PostStatus.FINISHED);
        long removed = Long.parseLong(service.create(author.getId(), taxi(TIME.plusSeconds(240))).data().postId()); lifecycle.remove(removed, admin.getId());
        var list = service.list(student.getId(), 0, 20, "ALL", null).data();
        assertThat(list.items().stream().map(PostResponse.ListItem::postId)).containsExactly("" + earlier, "" + first, "" + second);
        assertThat(service.list(student.getId(), 0, 20, null, "ALL").data().totalElements()).isEqualTo(6);
        rejects(POST_NOT_FOUND, () -> service.get(removed, author.getId()));
    }
    @Test void typeAndStateFiltersAndPaginationAgreeWithEffectiveStatus() {
        create(); service.create(author.getId(), delivery(TIME.plusSeconds(60)));
        assertThat(service.list(student.getId(), 0, 1, null, null).data().totalPages()).isEqualTo(2);
        assertThat(service.list(student.getId(), 1, 1, null, null).data().items().get(0).type()).isEqualTo(PostType.DELIVERY);
        assertThat(service.list(student.getId(), 0, 20, "TAXI", null).data().totalElements()).isEqualTo(1);
        assertThat(service.list(student.getId(), 5, 20, null, null).data().items()).isEmpty();
        clock.set(TIME);
        assertThat(service.list(student.getId(), 0, 20, null, "FINISHED").data().items().get(0).postStatus()).isEqualTo(PostStatus.FINISHED);
        assertThat(service.list(student.getId(), 0, 20, null, "OPEN").data().totalElements()).isEqualTo(1);
    }
    @Test void listCountsOnlyJoinedAndAuthorOnce() {
        long id = create(); participation.join(id, student.getId());
        assertThat(service.list(student.getId(), 0, 20, null, null).data().items().get(0).currentMemberCount()).isEqualTo(2);
        participation.cancel(id, student.getId());
        assertThat(service.list(student.getId(), 0, 20, null, null).data().items().get(0).currentMemberCount()).isEqualTo(1);
    }
    @Test void invalidFiltersAndMissingDatabaseActorAreRejected() {
        rejects(INVALID_QUERY_PARAMETER, () -> service.list(student.getId(), -1, 20, null, null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.list(student.getId(), 0, 51, null, null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.list(student.getId(), Integer.MAX_VALUE, 50, null, null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.list(student.getId(), 0, 20, "taxi", null));
        rejects(INVALID_QUERY_PARAMETER, () -> service.list(student.getId(), 0, 20, null, "REMOVED"));
        rejects(AUTHENTICATION_REQUIRED, () -> service.create(Long.MAX_VALUE, taxi(TIME)));
        rejects(AUTHENTICATION_REQUIRED, () -> service.list(Long.MAX_VALUE, 0, 20, null, null));
    }
    @Test void httpCreateReturnsLocationDetailEnvelopeAndOnlyAuthenticatedActor() throws Exception {
        String response = mvc.perform(post("/api/posts").with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(taxi(TIME))))
            .andExpect(status().isCreated()).andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.data.currentMemberCount").value(1)).andExpect(jsonPath("$.data.viewer.isAuthor").value(true))
            .andExpect(jsonPath("$.meta.evaluatedAt").exists()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(response).path("data").path("postId").asText();
        mvc.perform(get("/api/posts/" + id).with(as(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.communicationUrl").doesNotExist()).andExpect(jsonPath("$.data.author.displayName").value("작성자"));
        mvc.perform(patch("/api/posts/" + id).with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"HTTP 수정\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("HTTP 수정"));
    }
    @Test void httpListHasNoLinkOrPersonalIdentifiers() throws Exception {
        create(); mvc.perform(get("/api/posts").with(as(student)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].communicationUrl").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].authorId").doesNotExist()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @ParameterizedTest @ValueSource(strings = {"author", "student", "admin"})
    void REV_HTTP_physicalPostDeleteIsNotExposedAndDoesNotChangeData(String actor) throws Exception {
        long id = create(); participation.join(id, student.getId());
        User requester = switch (actor) { case "author" -> author; case "admin" -> admin; default -> student; };
        mvc.perform(delete("/api/posts/{id}", id).with(as(requester)).with(csrf()))
            .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.data").doesNotExist());
        Post saved = posts.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PostStatus.OPEN);
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(posts.count()).isEqualTo(1);
        assertThat(participations.countByPost_IdAndStatus(id, ParticipationStatus.JOINED)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"contentType", "accept", "malformedJson", "businessWithAccept", "malformedWithAccept"})
    void REV_HTTP_protocolErrorsUseTheDocumentedJsonEnvelope(String scenario) throws Exception {
        var request = switch (scenario) {
            case "contentType" -> post("/api/posts").contentType(MediaType.TEXT_PLAIN).content("{}");
            case "accept" -> get("/api/posts").accept(MediaType.TEXT_PLAIN);
            case "businessWithAccept" -> get("/api/posts/0").accept(MediaType.TEXT_PLAIN);
            case "malformedWithAccept" -> post("/api/posts").accept(MediaType.TEXT_PLAIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":");
            default -> post("/api/posts").contentType(MediaType.APPLICATION_JSON).content("{\"type\":");
        };
        boolean unsupportedAccept = scenario.equals("accept") || scenario.endsWith("WithAccept");
        int expectedStatus = scenario.equals("contentType") ? 415 : unsupportedAccept ? 406 : 400;
        String expectedCode = scenario.equals("contentType") ? "UNSUPPORTED_MEDIA_TYPE"
            : unsupportedAccept ? "NOT_ACCEPTABLE" : "INVALID_REQUEST";
        var result = mvc.perform(request.with(as(author)).with(csrf()))
            .andExpect(status().is(expectedStatus)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error.code").value(expectedCode))
            .andExpect(jsonPath("$.error.message").isNotEmpty()).andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var json = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("meta").path("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        assertThat(posts.count()).isZero();
    }

    @Test void REV_HTTP_successAndBusinessErrorMetadataMatchTheirResponseHeaders() throws Exception {
        var created = mvc.perform(post("/api/posts").with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(taxi(TIME)))).andExpect(status().isCreated())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.postId").isString()).andExpect(jsonPath("$.data.capacity").isNumber())
            .andExpect(jsonPath("$.data.joinable").isBoolean()).andExpect(jsonPath("$.error").doesNotExist()).andReturn();
        var success = mapper.readTree(created.getResponse().getContentAsString());
        assertThat(success.path("meta").path("requestId").asText()).isEqualTo(created.getResponse().getHeader("X-Request-Id"));
        long id = Long.parseLong(success.path("data").path("postId").asText());
        var denied = mvc.perform(patch("/api/posts/{id}", id).with(as(student)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"허용되면 안 되는 수정\"}")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"))
            .andExpect(jsonPath("$.data").doesNotExist()).andReturn();
        var failure = mapper.readTree(denied.getResponse().getContentAsString());
        assertThat(failure.path("meta").path("requestId").asText()).isEqualTo(denied.getResponse().getHeader("X-Request-Id"));
        assertThat(failure.path("meta").path("requestId").asText()).isNotEqualTo(success.path("meta").path("requestId").asText());
        assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("택시 동승");
    }

    @ParameterizedTest @ValueSource(strings = {"create", "update"})
    void REV_HTTP_unsupportedAcceptMustNotCommitPostMutation(String action) throws Exception {
        long id = action.equals("update") ? create() : 0;
        var request = action.equals("create")
            ? post("/api/posts").content(mapper.writeValueAsString(taxi(TIME)))
            : patch("/api/posts/{id}", id).content("{\"title\":\"실패 응답이면 저장되면 안 됨\"}");
        mvc.perform(request.with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.error.code").value("NOT_ACCEPTABLE"));
        assertThat(posts.count()).isEqualTo(id == 0 ? 0 : 1);
        if (id != 0) assertThat(posts.findById(id).orElseThrow().getTitle()).isEqualTo("택시 동승");
    }
    @Test void httpStrictInputRejectsForgedFieldsWrongTypesUnknownEnumsAndDuplicateKeys() throws Exception {
        String valid = mapper.writeValueAsString(taxi(TIME));
        for (String bad : List.of(valid.replace("\"capacity\":4", "\"capacity\":\"4\""),
            valid.replace("\"capacity\":4", "\"capacity\":4.5"), valid.replace("\"title\":\"택시 동승\"", "\"title\":true"),
            valid.replace("\"TAXI\"", "\"BUS\""), valid.substring(0, valid.length() - 1) + ",\"authorId\":999}",
            valid.substring(0, valid.length() - 1) + ",\"title\":\"중복 키\"}")) {
            mvc.perform(post("/api/posts").with(as(author)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(posts.count()).isZero();
    }
    @Test void httpAuthenticationCsrfAndOwnershipAreEnforced() throws Exception {
        long id = create();
        mvc.perform(get("/api/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/posts").with(as(author)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(taxi(TIME))))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(patch("/api/posts/" + id).with(as(student)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"위조\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
        mvc.perform(get("/api/posts").with(user("untrusted"))).andExpect(status().isUnauthorized());
    }
    @Test void httpBadIdMissingPostAndInvalidQueriesUseConsistentErrors() throws Exception {
        mvc.perform(get("/api/posts/0").with(as(student))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_POST_ID"));
        mvc.perform(get("/api/posts/9223372036854775808").with(as(student))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts/999999").with(as(student))).andExpect(status().isNotFound());
        mvc.perform(get("/api/posts?size=0").with(as(student))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts?type=BUS").with(as(student))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts?page=x").with(as(student))).andExpect(status().isBadRequest());
    }
    @Test void illegalPostRecoveryTransitionsAreRejected() {
        long id = create(); lifecycle.close(id, author.getId());
        assertThatThrownBy(() -> state(id, PostStatus.OPEN)).isInstanceOf(IllegalStateException.class);
        lifecycle.cancelRecruitment(id, author.getId());
        assertThatThrownBy(() -> state(id, PostStatus.OPEN)).isInstanceOf(IllegalStateException.class);
        long finished = Long.parseLong(service.create(author.getId(), taxi(TIME)).data().postId()); state(finished, PostStatus.FINISHED);
        assertThatThrownBy(() -> state(finished, PostStatus.OPEN)).isInstanceOf(IllegalStateException.class);
    }
    @Test void simultaneousFirstCreationHasExactlyOneWinner() throws Exception {
        List<String> results = race(() -> outcome(() -> service.create(author.getId(), taxi(TIME))),
            () -> outcome(() -> service.create(author.getId(), delivery(TIME))));
        assertThat(results).containsExactlyInAnyOrder("OK", "POST_TIME_CONFLICT"); assertThat(posts.count()).isEqualTo(1);
    }
    @Test void simultaneousTimeEditAndCreationHasExactlyOneWinner() throws Exception {
        long id = create(); Instant target = TIME.plusSeconds(60);
        List<String> results = race(() -> outcome(() -> service.update(id, author.getId(), edit("{\"referenceTime\":\"2026-10-12T18:01:00+09:00\"}"))),
            () -> outcome(() -> service.create(author.getId(), delivery(target))));
        assertThat(results).containsExactlyInAnyOrder("OK", "POST_TIME_CONFLICT");
        assertThat(posts.countConflicts(author.getId(), 0, target, NOW)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(PostType.class)
    void DUP_firstCreationsWaitOnSameAuthorRowAcrossTypes(PostType firstType) throws Exception {
        PostType secondType = firstType == PostType.TAXI ? PostType.DELIVERY : PostType.TAXI;
        assertThat(orderedAuthorMutation(() -> service.create(author.getId(), typedRequest(firstType, TIME)),
            () -> service.create(author.getId(), typedRequest(secondType, TIME)))).isEqualTo("POST_TIME_CONFLICT");
        assertThat(posts.count()).isEqualTo(1);
        assertThat(posts.countConflicts(author.getId(), 0, TIME, NOW)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void DUP_creationAndTimeChangeUseSameAuthorLockInBothOrders(boolean creationFirst) throws Exception {
        long moving = create(); Instant target = TIME.plusSeconds(60);
        UpdatePostRequest change = edit(mapper.writeValueAsString(Map.of("referenceTime", target.atOffset(ZoneOffset.UTC).toString())));
        Runnable creation = () -> service.create(author.getId(), delivery(target));
        Runnable update = () -> service.update(moving, author.getId(), change);
        assertThat(orderedAuthorMutation(creationFirst ? creation : update, creationFirst ? update : creation)).isEqualTo("POST_TIME_CONFLICT");
        assertThat(posts.countConflicts(author.getId(), 0, target, NOW)).isEqualTo(1);
        assertThat(posts.findById(moving).orElseThrow().getReferenceTime()).isEqualTo(creationFirst ? TIME : target);
        assertThat(posts.count()).isEqualTo(creationFirst ? 2 : 1);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void DUP_twoTimeChangesToSameTargetAreSerializedAcrossPostTypes(boolean taxiFirst) throws Exception {
        long taxiId = create();
        long deliveryId = Long.parseLong(service.create(author.getId(), delivery(TIME.plusSeconds(60))).data().postId());
        Instant target = TIME.plusSeconds(120);
        UpdatePostRequest change = edit(mapper.writeValueAsString(Map.of("referenceTime", target.atOffset(ZoneOffset.UTC).toString())));
        Runnable taxiUpdate = () -> service.update(taxiId, author.getId(), change);
        Runnable deliveryUpdate = () -> service.update(deliveryId, author.getId(), change);
        assertThat(orderedAuthorMutation(taxiFirst ? taxiUpdate : deliveryUpdate, taxiFirst ? deliveryUpdate : taxiUpdate)).isEqualTo("POST_TIME_CONFLICT");
        assertThat(posts.countConflicts(author.getId(), 0, target, NOW)).isEqualTo(1);
        assertThat(posts.findById(taxiId).orElseThrow().getReferenceTime()).isEqualTo(taxiFirst ? target : TIME);
        assertThat(posts.findById(deliveryId).orElseThrow().getReferenceTime()).isEqualTo(taxiFirst ? TIME.plusSeconds(60) : target);
    }
    @Test void DUP_differentAuthorsCanCommitSameTimeWhileFirstAuthorIsLocked() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> authorTransaction().executeWithoutResult(tx -> {
                service.create(author.getId(), taxi(TIME)); held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> second = pool.submit(() -> outcome(() -> service.create(student.getId(), delivery(TIME))));
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
            release.countDown(); first.get(10, TimeUnit.SECONDS);
            assertThat(posts.count()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }
    private TransactionTemplate authorTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
    private String orderedAuthorMutation(Runnable firstAction, Runnable waitingAction) throws Exception {
        CountDownLatch held = new CountDownLatch(1), started = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> authorTransaction().executeWithoutResult(tx -> {
                users.findByIdForUpdate(author.getId()).orElseThrow(); firstAction.run(); posts.flush();
                held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> waiting = pool.submit(() -> { started.countDown(); return outcome(waitingAction::run); });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            DatabaseLockAssertions.awaitPrimaryRowWait(jdbc, "users", waiting);
            release.countDown(); first.get(10, TimeUnit.SECONDS);
            return waiting.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
    }
    @Test void participantCommittedWhileEditWaitsLocksCoreFields() throws Exception {
        long id = create(); CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> join = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                posts.findByIdForUpdate(id).orElseThrow(); participation.join(id, student.getId()); held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> update = pool.submit(() -> outcome(() -> service.update(id, author.getId(), edit("{\"capacity\":2}"))));
            release.countDown(); join.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo("POST_CORE_FIELDS_LOCKED");
            assertThat(service.get(id, author.getId()).data().capacity()).isEqualTo(4);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void timeIsRecheckedAfterWaitingForAuthorLock() throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> lock = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                users.findByIdForUpdate(author.getId()).orElseThrow(); held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> creation = pool.submit(() -> outcome(() -> service.create(author.getId(), taxi(NOW.plusSeconds(660)))));
            clock.set(NOW.plusSeconds(60)); release.countDown(); lock.get(10, TimeUnit.SECONDS);
            assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo("POST_REFERENCE_TIME_TOO_EARLY"); assertThat(posts.count()).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void updateRejectsExpiryAfterWaitingForPostLock() throws Exception {
        long id = create(); CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> lock = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                posts.findByIdForUpdate(id).orElseThrow(); held.countDown(); await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> update = pool.submit(() -> outcome(() -> service.update(id, author.getId(), edit("{\"title\":\"늦은 수정\"}"))));
            clock.set(TIME); release.countDown(); lock.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo("POST_FINISHED");
            assertThat(service.get(id, author.getId()).data().title()).isEqualTo("택시 동승");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private List<String> race(Callable<String> a, Callable<String> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch go = new CountDownLatch(1);
        try {
            Future<String> one = pool.submit(() -> { await(go); return a.call(); });
            Future<String> two = pool.submit(() -> { await(go); return b.call(); });
            go.countDown(); return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private String outcome(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) throws Exception {
        try { operation.call(); return "OK"; }
        catch (ApiException e) { return e.code().name(); }
        catch (Throwable e) { throw new RuntimeException(e); }
    }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test latch"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
