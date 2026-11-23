package com.school.party.domain.community;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the final privacy contract on a disposable MySQL 8.4 container (Flyway V1/V2 schema, as deployed),
 * plus MySQL-only checks of the migrated schema and of the session settings added for issue #30.
 * Excluded from the default test task; run with {@code ./gradlew mysqlTest} (Docker required).
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class)
@AutoConfigureMockMvc @Import(TestClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FinalVerificationMySqlIT extends FinalVerificationContract {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired PlatformTransactionManager transactions;

    // ------------------------------------------------------------- 11/23 migration result
    @Test void MIG01_flywayHistoryHasExactlyTheTwoRepositoryMigrationsSucceeded() {
        List<Map<String, Object>> history = jdbc.queryForList(
            "select version, type, success from flyway_schema_history order by installed_rank");
        assertThat(history).extracting(r -> r.get("version") + ":" + r.get("type") + ":" + r.get("success"))
            .containsExactly("1:SQL:true", "2:SQL:true");
    }

    @Test void MIG02_everyConstraintDefinedInV1AndV2ExistsOnTheServer() {
        List<String> names = jdbc.queryForList("select constraint_name from information_schema.table_constraints "
            + "where table_schema = database()", String.class);
        assertThat(names).contains("fk_user_auth_user", "fk_local_credential_user", "fk_refresh_token_user", "uq_user_auth_provider",
            "fk_post_author", "fk_post_deleted_by", "fk_participation_post", "fk_participation_user", "uq_participation_post_user",
            "ck_post_type", "ck_post_status", "ck_post_capacity", "ck_post_fields", "ck_post_places", "ck_post_text",
            "ck_post_reference_minute", "ck_participation_state");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema = database() "
            + "and table_name = 'users' and non_unique = 0 and column_name in ('student_no','school_email')", Long.class)).isEqualTo(2);
    }

    // -------------------------------------------------------------- 11/23 data consistency
    @Test void DB01_serviceTransactionsReallyRunReadCommittedOnTheServer() {
        // useLocalSessionState (issue #30) answers isolation from the driver's cache; ask the server itself.
        TransactionTemplate readCommitted = new TransactionTemplate(transactions);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(isolation(readCommitted)).isEqualTo("READ-COMMITTED");
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThat(isolation(readOnly)).isEqualTo("READ-COMMITTED");
        TransactionTemplate repeatable = new TransactionTemplate(transactions);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(isolation(repeatable)).isEqualTo("REPEATABLE-READ");
        // The pooled connection must return to the configured level afterwards.
        assertThat(isolation(readCommitted)).isEqualTo("READ-COMMITTED");
    }

    private String isolation(TransactionTemplate template) {
        String level = template.execute(tx -> jdbc.queryForObject("select @@transaction_isolation", String.class));
        return level;
    }

    @Test void DB02_koreanNamesAndTitlesAreStoredAsUtf8mb4() {
        assertThat(jdbc.queryForList("select table_collation from information_schema.tables where table_schema = database() "
            + "and table_name in ('users','post','participation','user_auth','local_credential')", String.class))
            .hasSize(5).allMatch(c -> c.startsWith("utf8mb4"));
        assertThat(jdbc.queryForObject("select char_length(name) from users where id = ?", Integer.class, author.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select name from users where id = ?", String.class, author.getId())).isEqualTo("정하은");
    }
}
