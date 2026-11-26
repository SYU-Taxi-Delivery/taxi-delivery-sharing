package com.school.party.domain.community;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
/** Same contract on MySQL 8.4 with the Flyway schema (V3 CHECK and UNIQUE constraints, utf8mb4 lengths). */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class) @AutoConfigureMockMvc @Import(TestClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CommentReportMySqlIT extends CommentReportContract {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
