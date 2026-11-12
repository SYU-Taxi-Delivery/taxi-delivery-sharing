package com.school.party.domain.community;

import com.school.party.domain.community.domain.Post;
import com.school.party.domain.community.repository.ParticipationRepository;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL 8.4 reports CHECK violations as error 3819 with SQLState HY000. Both data-access paths must
 * classify them as {@link DataIntegrityViolationException}, as already happens on MariaDB and H2.
 * Excluded from the default test task; run with {@code ./gradlew mysqlTest} (Docker required).
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class)
@Import(TestClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ConstraintTranslationMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired PostRepository posts;
    @Autowired ParticipationRepository participations;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    Post post;

    @BeforeEach void arrange() {
        clean(); clock.set(NOW);
        User author = users.save(User.builder().studentNo("check-author").schoolEmail("check-author@syuin.ac.kr")
            .name("작성자").role(Role.STUDENT).status(UserStatus.ACTIVE).build());
        post = posts.save(Post.taxi(author, 4, Instant.parse("2026-10-06T09:00:00Z"), NOW));
    }
    @AfterEach void clean() { participations.deleteAllInBatch(); posts.deleteAllInBatch(); users.deleteAllInBatch(); }

    @Test void jdbcTemplateClassifiesCheckViolationAsDataIntegrityViolation() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4");
        assertThatThrownBy(() -> jdbc.update("update post set capacity = 0 where id = ?", post.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select capacity from post where id = ?", Integer.class, post.getId())).isEqualTo(4);
    }

    @Test void jpaFlushClassifiesCheckViolationAsDataIntegrityViolation() {
        Post loaded = posts.findById(post.getId()).orElseThrow();
        ReflectionTestUtils.setField(loaded, "capacity", 0);
        assertThatThrownBy(() -> posts.saveAndFlush(loaded)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select capacity from post where id = ?", Integer.class, post.getId())).isEqualTo(4);
    }

    @Test void existingMySqlCodesKeepTheirMoreSpecificTypes() {
        assertThatThrownBy(() -> jdbc.update("insert into users(student_no, name, school_email, role, status, created_at, updated_at) "
            + "values ('check-author', 'x', 'other@syuin.ac.kr', 'STUDENT', 'ACTIVE', now(6), now(6))"))
            .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("update post set author_id = ? where id = ?", Long.MAX_VALUE, post.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
