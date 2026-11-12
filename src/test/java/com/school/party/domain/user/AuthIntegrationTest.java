package com.school.party.domain.user;

import com.school.party.domain.community.TestClockConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Runs the authentication contract on the default H2 test database. */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    properties = "spring.datasource.url=jdbc:h2:mem:auth-contract;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test")
@Import({TestClockConfiguration.class, AuthContract.RoleProbeController.class})
class AuthIntegrationTest extends AuthContract {}
