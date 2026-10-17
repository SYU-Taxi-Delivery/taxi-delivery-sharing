package com.school.party.domain.community;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Observe actual InnoDB row-lock contention for both aggregate and author mutex rows. */
final class DatabaseLockAssertions {
    private DatabaseLockAssertions() {}
    static void awaitPrimaryRowWait(JdbcTemplate jdbc, String table, Future<?> waiting) throws Exception {
        String product = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        if (product.equalsIgnoreCase("MariaDB")) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).until(() ->
                jdbc.queryForObject("select count(*) from information_schema.innodb_lock_waits w join information_schema.innodb_locks l on l.lock_id = w.requested_lock_id where l.lock_table = concat('`', database(), '`.`', ?, '`') and l.lock_index = 'PRIMARY'", Integer.class, table) > 0);
        } else if (product.equalsIgnoreCase("MySQL")) {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).until(() ->
                jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.engine_lock_id = w.requesting_engine_lock_id and l.engine = w.engine where l.object_schema = database() and l.object_name = ? and l.index_name = 'PRIMARY'", Integer.class, table) > 0);
        } else {
            assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }
        assertThat(waiting.isDone()).isFalse();
    }
}
