package com.school.party.domain.community;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class) @AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class PostIntegrationTest extends PostContract {}
