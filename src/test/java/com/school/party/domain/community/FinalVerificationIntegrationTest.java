package com.school.party.domain.community;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Runs the final privacy contract on H2. */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    properties = "spring.datasource.url=jdbc:h2:mem:final-verification;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class FinalVerificationIntegrationTest extends FinalVerificationContract {}
