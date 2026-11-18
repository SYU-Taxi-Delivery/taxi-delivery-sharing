package com.school.party.domain.community;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the recruitment consistency cases (author close/cancel, search, my posts, races) on a
 * disposable MySQL 8.4 container. The H2 "test" profile is not inherited, so the schema comes
 * from Flyway V1/V2 and is checked by Hibernate validate, as in deployment.
 * Excluded from the default test task; run with {@code ./gradlew mysqlTest} (Docker required).
 */
@ActiveProfiles(inheritProfiles = false)
@Testcontainers(disabledWithoutDocker = true)
class RecruitmentConsistencyMySqlIT extends RecruitmentConsistencyTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
