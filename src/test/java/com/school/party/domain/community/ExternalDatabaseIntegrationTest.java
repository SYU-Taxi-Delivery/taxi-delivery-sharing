package com.school.party.domain.community;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class) @AutoConfigureMockMvc @Import(TestClockConfiguration.class)
@EnabledIfEnvironmentVariable(named = "PARTICIPATION_TEST_DB_URL", matches = ".+")
class ExternalDatabaseIntegrationTest extends ParticipationContract {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PARTICIPATION_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("PARTICIPATION_TEST_DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("PARTICIPATION_TEST_DB_PASSWORD"));
    }
}
