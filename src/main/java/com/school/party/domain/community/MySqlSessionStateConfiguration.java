package com.school.party.domain.community;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cuts session-statement round trips on MySQL (issue #30).
 *
 * <p>Every transaction used to ask the server for its isolation level and re-send autocommit,
 * isolation and read-only settings even when nothing changed. The deployed DB is in another
 * region than the app, so each of those round trips adds visible latency.
 *
 * <ul>
 *   <li>{@code useLocalSessionState}: Connector/J answers isolation/autocommit/read-only from the
 *       state it tracks and skips setters that would not change anything. All changes go through
 *       the JDBC API (Spring/Hibernate), never raw SQL, so the tracked state stays correct.</li>
 *   <li>{@code readOnlyPropagatesToServer=false}: read-only transactions no longer send
 *       {@code SET SESSION TRANSACTION READ ONLY/WRITE}. Read-only service methods never write,
 *       and Hibernate does not flush them, so the server-side guard only cost two round trips.</li>
 * </ul>
 *
 * Applied only to MySQL URLs, so H2 test and demo databases are unaffected. Set on the pool
 * here instead of in DB_URL so the deployed environment variables do not need to change.
 */
@Configuration
public class MySqlSessionStateConfiguration {
    @Bean
    static BeanPostProcessor mySqlSessionStatePostProcessor() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null
                        && hikari.getJdbcUrl().startsWith("jdbc:mysql:")) {
                    hikari.addDataSourceProperty("useLocalSessionState", "true");
                    hikari.addDataSourceProperty("readOnlyPropagatesToServer", "false");
                }
                return bean;
            }
        };
    }
}
