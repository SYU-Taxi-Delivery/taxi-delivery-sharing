package com.school.party.domain.community;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the integrity and access contract on a disposable MySQL 8.4 container (Flyway V1/V2 schema).
 * Excluded from the default test task; run with {@code ./gradlew mysqlTest} (Docker required).
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class)
@AutoConfigureMockMvc @Import(TestClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class IntegrityAccessMySqlIT extends IntegrityAccessContract {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
