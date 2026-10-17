package com.school.party.domain.community;

import com.school.party.domain.community.domain.Participation;
import com.school.party.domain.community.domain.Post;
import com.school.party.domain.user.entity.*;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

/** Opt-in rehearsal on a local disposable server; never uses the application's DB_URL. */
@EnabledIfEnvironmentVariable(named = "MIGRATION_REHEARSAL_ADMIN_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDatabaseMigrationTest {
    private static final List<String> USER_TABLES = List.of("users", "user_auth", "local_credential", "refresh_token");
    private final List<String> createdSchemas = new ArrayList<>();
    private String adminUrl;
    private String username;
    private String password;
    private String schema;
    private Map<String, List<String>> referenceStructure;

    @BeforeAll void createV1Reference() throws Exception {
        adminUrl = System.getenv("MIGRATION_REHEARSAL_ADMIN_URL");
        assertThat(adminUrl).matches("jdbc:(mariadb|mysql)://(127\\.0\\.0\\.1|localhost):[0-9]+/mysql(?:\\?.*)?");
        username = System.getenv("MIGRATION_REHEARSAL_USERNAME");
        password = System.getenv("MIGRATION_REHEARSAL_PASSWORD");
        schema = createSchema();
        Flyway.configure().dataSource(url(), username, password).target("1").cleanDisabled(true).load().migrate();
        referenceStructure = userStructure();
        assertThat(referenceStructure.get("tables")).hasSize(4);
    }

    @BeforeEach void newIsolatedSchema() throws Exception { schema = createSchema(); }

    @AfterAll void dropOnlySchemasCreatedByThisRun() throws Exception {
        if (createdSchemas.isEmpty()) return;
        try (var connection = DriverManager.getConnection(adminUrl, username, password);
             var statement = connection.createStatement()) {
            for (String created : createdSchemas) {
                if (!created.matches("migration_revalidation_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected schema name");
                statement.execute("DROP DATABASE `" + created + "`");
            }
        }
    }

    @Test void newDatabaseExecutesBothSqlMigrationsWithoutBaseline() throws Exception {
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(2);
        assertThat(query("select concat(version, ':', type) from flyway_schema_history order by installed_rank"))
            .containsExactly("1:SQL", "2:SQL");
        assertThat(userStructure()).isEqualTo(referenceStructure);
        try (var ignored = sessionFactory("validate", true)) { }
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }

    @Test void existingJpaUserSchemaCanBeComparedBaselinedAndMigratedWithoutLosingData() throws Exception {
        existingUsers();
        // The legacy fixture is generated from JPA, independently of V1 SQL.
        try (var connection = DriverManager.getConnection(url(), username, password)) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/inspect-user-schema.sql"));
        }
        var dataBefore = userData();
        assertThat(userStructure()).isNotEqualTo(referenceStructure);
        assertThat(userStructure().get("columns")).anyMatch(c -> c.contains("revoked, bit(1)"));
        // Reviewed alignment for this disposable fixture, not an automatic production conversion.
        execute("alter table refresh_token modify revoked boolean not null");
        assertThat(userData()).isEqualTo(dataBefore);
        assertThat(userStructure()).isEqualTo(referenceStructure);
        var structureBefore = userStructure();
        var sequencesBefore = query("select table_name,auto_increment from information_schema.tables "
            + "where table_schema=database() and table_name in ('users','user_auth','local_credential','refresh_token') order by table_name");
        var migration = flyway();
        migration.baseline();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(query("select concat(version, ':', type) from flyway_schema_history order by installed_rank"))
            .containsExactly("1:BASELINE", "2:SQL");
        assertThat(userStructure()).isEqualTo(structureBefore);
        assertThat(userData()).isEqualTo(dataBefore);
        assertThat(query("select table_name,auto_increment from information_schema.tables "
            + "where table_schema=database() and table_name in ('users','user_auth','local_credential','refresh_token') order by table_name"))
            .isEqualTo(sequencesBefore);
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThat(migration.migrate().migrationsExecuted).isZero();
        // Validate all six entity mappings and reference preserved user IDs from new tables.
        try (var factory = sessionFactory("validate", true); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            Post post = Post.taxi(session.find(User.class, 1017L), 4, now.plusSeconds(3600), now);
            session.persist(post);
            session.persist(new Participation(post, session.find(User.class, 1043L), now));
            tx.commit();
        }
        assertThat(query("select cast(author_id as char) from post")).containsExactly("1017");
        assertThat(query("select cast(user_id as char) from participation")).containsExactly("1043");
        assertThat(userData()).isEqualTo(dataBefore);
        assertThatThrownBy(() -> execute("insert into participation(post_id,user_id,status,created_at,updated_at) "
            + "select id,999999,'JOINED',now(6),now(6) from post")).isInstanceOf(SQLException.class);
    }

    @Test void existingNonemptySchemaIsNotAutomaticallyBaselined() throws Exception {
        existingUsers();
        var before = userData();
        assertThat(flyway().getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(FlywayException.class)
            .hasMessageContaining("non-empty schema");
        assertThat(query("select table_name from information_schema.tables where table_schema=database() "
            + "and table_name in ('post','participation','flyway_schema_history')")).isEmpty();
        assertThat(userData()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "alter table users modify role enum('STUDENT','ADMIN') not null",
        "alter table users modify school_email varchar(99) not null"
    })
    void schemaComparisonDetectsLegacyEnumAndColumnLengthDifferences(String alteration) throws Exception {
        existingUsers();
        execute("alter table refresh_token modify revoked boolean not null");
        assertThat(userStructure()).isEqualTo(referenceStructure);
        execute(alteration);
        assertThat(userStructure()).isNotEqualTo(referenceStructure);
        assertThat(userStructure().get("columns")).isNotEqualTo(referenceStructure.get("columns"));
        // Comparison only: baseline and migrations must wait for a reviewed conversion plan.
        assertThat(query("select table_name from information_schema.tables where table_schema=database() "
            + "and table_name='flyway_schema_history'")).isEmpty();
    }

    @Test void independentlyGeneratedJpaSchemaExposesBooleanStorageDifference() throws Exception {
        existingUsers();
        assertThat(userStructure()).isNotEqualTo(referenceStructure);
        assertThat(userStructure().get("columns")).anyMatch(c -> c.contains("revoked, bit(1)"));
        assertThat(referenceStructure.get("columns")).anyMatch(c -> c.contains("revoked, tinyint(1)"));
        assertThat(query("select table_name from information_schema.tables where table_schema=database() "
            + "and table_name='flyway_schema_history'")).isEmpty();
    }

    @Test void baselineDoesNotProveThatAnIncompleteSchemaMatchesV1() throws Exception {
        existingUsers();
        execute("drop table refresh_token");
        assertThat(userStructure()).isNotEqualTo(referenceStructure);
        // Deliberately bypass the comparison in this negative test to demonstrate Flyway's scope.
        var migration = flyway();
        migration.baseline();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(migration.validateWithResult().validationSuccessful).isTrue();
        assertThatThrownBy(() -> {
            try (var ignored = sessionFactory("validate", true)) { }
        }).hasMessageContaining("missing table [refresh_token]");
        assertThat(query("select concat(version, ':', type) from flyway_schema_history order by installed_rank"))
            .containsExactly("1:BASELINE", "2:SQL");
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(url(), username, password).locations("classpath:db/migration")
            .baselineVersion("1").baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private String createSchema() throws Exception {
        String created = "migration_revalidation_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + created + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        createdSchemas.add(created);
        return created;
    }

    private String url() { return adminUrl.replace("/mysql", "/" + schema); }

    private SessionFactory sessionFactory(String ddl, boolean community) {
        var registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", url())
            .applySetting("hibernate.connection.username", username)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.hbm2ddl.auto", ddl)
            .applySetting("hibernate.hbm2ddl.halt_on_error", true)
            .applySetting("hibernate.jdbc.time_zone", "UTC")
            .applySetting("hibernate.physical_naming_strategy", PhysicalNamingStrategySnakeCaseImpl.class.getName())
            .build();
        try {
            var sources = new MetadataSources(registry).addAnnotatedClass(User.class).addAnnotatedClass(UserAuth.class)
                .addAnnotatedClass(LocalCredential.class).addAnnotatedClass(RefreshToken.class);
            if (community) sources.addAnnotatedClass(Post.class).addAnnotatedClass(Participation.class);
            return sources.buildMetadata().buildSessionFactory();
        } catch (RuntimeException ex) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw ex;
        }
    }

    private void existingUsers() throws Exception {
        try (var ignored = sessionFactory("create-only", false)) { }
        execute("insert into users(id,student_no,name,school_email,role,status,created_at,updated_at) values "
            + "(1017,'migration-author','기존작성자','migration-author@syu.ac.kr','STUDENT','ACTIVE','2026-10-01 01:02:03.123456','2026-10-02 04:05:06.654321'),"
            + "(1043,'migration-member','기존참여자','migration-member@syu.ac.kr','STUDENT','ACTIVE','2026-10-01 01:02:03.123456','2026-10-02 04:05:06.654321')");
        execute("insert into local_credential(user_id,password_hash,failed_login_count,locked_until,password_updated_at) values "
            + "(1017,'synthetic-test-hash-not-a-real-password',2,'2026-10-01 03:04:05.123456','2026-10-01 01:02:03.123456')");
        execute("insert into user_auth(id,user_id,provider,provider_user_id,linked_at,last_used_at) values "
            + "(2017,1017,'LOCAL','migration-author','2026-10-01 01:02:03.123456','2026-10-02 04:05:06.654321')");
        execute("insert into refresh_token(id,user_id,token_hash,expires_at,revoked,created_at) values "
            + "(3017,1017,'synthetic-test-token-not-a-real-token','2026-11-01 01:02:03.123456',false,'2026-10-01 01:02:03.123456'),"
            + "(3018,1043,'synthetic-revoked-test-token','2026-11-02 01:02:03.123456',true,'2026-10-02 01:02:03.123456')");
    }

    private Map<String, List<String>> userData() throws Exception {
        Map<String, List<String>> data = new TreeMap<>();
        for (String table : USER_TABLES) {
            // BIT and BOOLEAN values are compared semantically while all other stored values remain exact.
            String select = table.equals("refresh_token")
                ? "select id,user_id,token_hash,expires_at,cast(revoked as unsigned),created_at from refresh_token order by id"
                : "select * from " + table + " order by 1";
            data.put(table, query(select));
        }
        return data;
    }

    private Map<String, List<String>> userStructure() throws Exception {
        String tables = "('users','user_auth','local_credential','refresh_token')";
        Map<String, List<String>> snapshot = new TreeMap<>();
        snapshot.put("tables", query("select table_name,engine,table_collation from information_schema.tables "
            + "where table_schema=database() and table_name in " + tables + " order by table_name"));
        snapshot.put("columns", query("select table_name,column_name,column_type,is_nullable,column_default,extra,"
            + "character_set_name,collation_name from information_schema.columns where table_schema=database() "
            + "and table_name in " + tables + " order by table_name,column_name"));
        // Compare key column order, not Hibernate-generated names or redundant FK backing indexes.
        snapshot.put("uniqueKeys", query("select table_name,case when index_name='PRIMARY' then 'PK' else 'UNIQUE' end,"
            + "group_concat(column_name order by seq_in_index separator ',') from information_schema.statistics "
            + "where table_schema=database() and non_unique=0 and table_name in " + tables
            + " group by table_name,index_name order by 1,2,3"));
        snapshot.put("foreignKeys", query("select k.table_name,k.column_name,"
            + "case when k.referenced_table_schema=database() then '<current>' else k.referenced_table_schema end,"
            + "k.referenced_table_name,k.referenced_column_name,r.update_rule,r.delete_rule "
            + "from information_schema.key_column_usage k join information_schema.referential_constraints r "
            + "on k.constraint_schema=r.constraint_schema and k.table_name=r.table_name and k.constraint_name=r.constraint_name "
            + "where k.table_schema=database() and k.table_name in " + tables
            + " and k.referenced_table_name is not null order by 1,2,3,4,5"));
        return snapshot;
    }

    private List<String> query(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url(), username, password);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) row.add(rows.getString(i));
                values.add(row.toString());
            }
            // Single-column queries retain their actual value for readable history assertions.
            if (rows.getMetaData().getColumnCount() == 1)
                return values.stream().map(v -> v.substring(1, v.length() - 1)).toList();
            return values;
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(url(), username, password);
             var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
