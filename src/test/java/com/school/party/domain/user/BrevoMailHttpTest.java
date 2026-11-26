package com.school.party.domain.user;

import com.school.party.domain.community.TestClockConfiguration;
import com.school.party.domain.user.mail.BrevoMailSender;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #51: with BREVO_API_KEY set, the signup code goes out through Brevo's HTTPS API, and a
 * Brevo failure keeps the existing behaviour (503 MAIL_DELIVERY_FAILED, code discarded so an
 * immediate retry is not throttled).
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    properties = "spring.datasource.url=jdbc:h2:mem:brevo-mail;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class BrevoMailHttpTest {
    private static final FakeBrevo BREVO = new FakeBrevo();

    @DynamicPropertySource static void brevo(DynamicPropertyRegistry registry) {
        registry.add("app.mail.brevo.api-key", () -> "xkeysib-test-key");
        registry.add("app.mail.brevo.base-url", BREVO::baseUrl);
        registry.add("app.mail.brevo.timeout", () -> "2s");
        registry.add("app.mail.from", () -> "sender@example.com");
    }

    @Autowired MockMvc mvc;
    @Autowired JavaMailSender mailSender;

    @BeforeEach void reset() { BREVO.reset(); }

    private org.springframework.test.web.servlet.ResultActions sendCode(String email) throws Exception {
        return mvc.perform(post("/api/auth/school-email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"schoolEmail\":\"" + email + "\"}"));
    }

    /** Both SchoolEmailAuthService and PasswordResetService inject this one JavaMailSender bean. */
    @Test void brevoReplacesSmtpWhenTheKeyIsSet() {
        assertThat(mailSender).isInstanceOf(BrevoMailSender.class);
    }

    @Test void signupCodeIsSentThroughBrevo() throws Exception {
        sendCode("20249001@syuin.ac.kr").andExpect(status().isOk());
        assertThat(BREVO.requests).hasSize(1);
        assertThat(BREVO.requests.get(0).body()).contains("20249001@syuin.ac.kr").containsPattern("\\[\\d{6}]");
    }

    @Test void brevoErrorAnswers503AndAllowsAnImmediateRetry() throws Exception {
        BREVO.answer(500, "{\"code\":\"internal_error\"}");
        sendCode("20249002@syuin.ac.kr").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("MAIL_DELIVERY_FAILED"));
        BREVO.answer(201, "{\"messageId\":\"<ok@brevo>\"}");
        sendCode("20249002@syuin.ac.kr").andExpect(status().isOk());
    }

    @Test void silentBrevoFailsFast() throws Exception {
        BREVO.goSilent();
        long started = System.nanoTime();
        sendCode("20249003@syuin.ac.kr").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("MAIL_DELIVERY_FAILED"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @AfterAll static void close() { BREVO.close(); }
}
