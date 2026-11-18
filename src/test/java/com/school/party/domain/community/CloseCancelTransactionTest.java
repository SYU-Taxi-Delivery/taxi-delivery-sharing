package com.school.party.domain.community;

import com.school.party.domain.community.domain.ParticipationStatus;
import com.school.party.domain.community.domain.PostStatus;
import com.school.party.domain.community.domain.PostType;
import com.school.party.domain.community.post.CreatePostRequest;
import com.school.party.domain.community.post.PostService;
import com.school.party.domain.community.repository.ParticipationRepository;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.community.security.CampusUser;
import com.school.party.domain.community.service.ParticipationService;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #35: the author's close/cancel and the detail in its answer happen in one transaction.
 * If the answer cannot be built, the change is rolled back, so a retry is not rejected with 409.
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class)
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class CloseCancelTransactionTest {
    static final Instant NOW = Instant.parse("2026-11-13T14:30:00Z");
    @Autowired MockMvc mvc;
    @Autowired PostService service;
    @Autowired ParticipationService participation;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    @MockitoSpyBean ParticipationRepository participations;
    User author, student;
    long postId;

    @BeforeEach void arrange() {
        reset(participations);
        participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); clock.set(NOW);
        author = users.save(account("작성자")); student = users.save(account("참여자"));
        Instant at = NOW.plus(Duration.ofHours(2));
        postId = Long.parseLong(service.create(author.getId(), new CreatePostRequest(PostType.TAXI, "석계역 → 삼육대학교", "같이 가요",
            4, at.atOffset(ZoneOffset.ofHours(9)), "https://open.kakao.com/o/test", "석계역", "삼육대학교", null, null, null)).data().postId());
        participation.join(postId, student.getId());
    }

    @Test void closeIsRolledBackWhenItsAnswerFailsAndCanBeRetried() throws Exception {
        failCountingMembers();
        mvc.perform(post("/api/posts/{id}/close", postId).with(as(author)).with(csrf()))
            .andExpect(status().isServiceUnavailable());
        assertThat(posts.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);

        reset(participations);
        mvc.perform(post("/api/posts/{id}/close", postId).with(as(author)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.postStatus").value("CLOSED"))
            .andExpect(jsonPath("$.data.recruitmentStatus").value("CLOSED"))
            .andExpect(jsonPath("$.data.currentMemberCount").value(2));
    }

    @Test void cancelIsRolledBackWhenItsAnswerFailsAndCanBeRetried() throws Exception {
        failCountingMembers();
        mvc.perform(post("/api/posts/{id}/cancel", postId).with(as(author)).with(csrf()))
            .andExpect(status().isServiceUnavailable());
        assertThat(posts.findById(postId).orElseThrow().getStatus()).isEqualTo(PostStatus.OPEN);

        reset(participations);
        // The member cancelled by the failed attempt is back: the whole change was rolled back.
        assertThat(participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED)).isEqualTo(1);
        mvc.perform(post("/api/posts/{id}/cancel", postId).with(as(author)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.postStatus").value("CANCELLED"))
            .andExpect(jsonPath("$.data.currentMemberCount").value(1));
        assertThat(participations.countByPost_IdAndStatus(postId, ParticipationStatus.JOINED)).isZero();
    }

    /** Simulates the database failing while the answer is built, after the change was made. */
    private void failCountingMembers() {
        doThrow(new TransientDataAccessResourceException("simulated outage"))
            .when(participations).countByPost_IdAndStatus(anyLong(), eq(ParticipationStatus.JOINED));
    }

    private static RequestPostProcessor as(User u) { return user(new CampusUser(u.getId(), u.getName(), "unused", u.getRole())); }

    private static User account(String name) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syuin.ac.kr").name(name).role(Role.STUDENT).status(UserStatus.ACTIVE).build();
    }
}
