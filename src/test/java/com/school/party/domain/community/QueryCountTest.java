package com.school.party.domain.community;

import com.school.party.domain.community.domain.PostType;
import com.school.party.domain.community.post.CreatePostRequest;
import com.school.party.domain.community.post.PostService;
import com.school.party.domain.community.repository.ParticipationRepository;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.community.service.ParticipationService;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** Issues #30 and #32: SQL statement counts must not grow with the page size or repeat lookups. */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test") @Import(TestClockConfiguration.class)
class QueryCountTest {
    static final Instant NOW = Instant.parse("2026-11-13T14:30:00Z");
    @Autowired PostService posts;
    @Autowired ParticipationService participation;
    @Autowired PostRepository postRepository;
    @Autowired ParticipationRepository participations;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    @Autowired EntityManagerFactory emf;
    Statistics stats;
    User student;

    @BeforeEach void arrange() {
        participations.deleteAllInBatch(); postRepository.deleteAllInBatch(); users.deleteAllInBatch(); clock.set(NOW);
        student = users.save(user("참여자"));
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
    }

    @Test void historyQueryCountDoesNotGrowWithDifferentAuthors() {
        joinPostByNewAuthor(1);
        long one = historyStatements();
        for (int i = 2; i <= 5; i++) joinPostByNewAuthor(i);
        long five = historyStatements();
        assertThat(participation.history(student.getId(), 0, 20, null).data().items()).hasSize(5);
        // Before #32 every distinct author added one SELECT on users.
        assertThat(five).isEqualTo(one);
    }

    @Test void joinLoadsThePostOnce() {
        long postId = createPost(users.save(user("작성자")), 1);
        stats.clear();
        participation.join(postId, student.getId());
        // Before #30 the locked post was selected twice (locking query + refresh).
        assertThat(stats.getEntityStatistics("com.school.party.domain.community.domain.Post").getLoadCount()).isEqualTo(1);
    }

    private long historyStatements() {
        stats.clear();
        participation.history(student.getId(), 0, 20, null);
        return stats.getPrepareStatementCount();
    }

    private void joinPostByNewAuthor(int n) {
        long postId = createPost(users.save(user("작성자" + n)), n);
        participation.join(postId, student.getId());
    }

    private long createPost(User author, int n) {
        Instant at = NOW.plus(Duration.ofHours(1 + n));
        return Long.parseLong(posts.create(author.getId(), new CreatePostRequest(PostType.TAXI, "석계역 → 삼육대학교 " + n, "같이 가요",
            4, at.atOffset(ZoneOffset.ofHours(9)), "https://open.kakao.com/o/test", "석계역", "삼육대학교", null, null, null)).data().postId());
    }

    private static User user(String name) {
        String key = UUID.randomUUID().toString().substring(0, 16);
        return User.builder().studentNo(key).schoolEmail(key + "@syu.ac.kr").name(name).role(Role.STUDENT).status(UserStatus.ACTIVE).build();
    }
}
