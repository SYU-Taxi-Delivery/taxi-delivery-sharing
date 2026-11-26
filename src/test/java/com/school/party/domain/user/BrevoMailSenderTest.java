package com.school.party.domain.user;

import com.school.party.domain.user.mail.BrevoMailSender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #51: Brevo HTTPS sender request shape, error mapping and timeout, against a local fake. */
class BrevoMailSenderTest {
    private static final String KEY = "xkeysib-test-key";
    private final ObjectMapper json = JsonMapper.builder().build();
    private final FakeBrevo brevo = new FakeBrevo();

    @AfterEach void stop() { brevo.close(); }

    private BrevoMailSender sender(Duration timeout) {
        return new BrevoMailSender(json, brevo.baseUrl() + "/", KEY, "sender@example.com", "택시·배달 합승", timeout);
    }

    private static SimpleMailMessage message() {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setTo("20240001@syuin.ac.kr");
        m.setSubject("[택시·배달 합승] 학교 학생 인증 번호 안내");
        m.setText("인증 번호는 [123456] 입니다.");
        return m;
    }

    @Test void postsTheMessageAsBrevoTransactionalEmail() {
        sender(Duration.ofSeconds(5)).send(message());

        assertThat(brevo.requests).hasSize(1);
        FakeBrevo.Request r = brevo.requests.get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/v3/smtp/email");
        assertThat(r.apiKey()).isEqualTo(KEY);
        assertThat(r.contentType()).startsWith("application/json");
        JsonNode body = json.readTree(r.body());
        assertThat(body.path("sender").path("email").asString()).isEqualTo("sender@example.com");
        assertThat(body.path("sender").path("name").asString()).isEqualTo("택시·배달 합승");
        assertThat(body.path("to")).hasSize(1);
        assertThat(body.path("to").get(0).path("email").asString()).isEqualTo("20240001@syuin.ac.kr");
        assertThat(body.path("subject").asString()).isEqualTo("[택시·배달 합승] 학교 학생 인증 번호 안내");
        assertThat(body.path("textContent").asString()).isEqualTo("인증 번호는 [123456] 입니다.");
    }

    @Test void rejectedRequestBecomesMailSendExceptionWithoutLeakingTheKeyOrAddress() {
        brevo.answer(401, "{\"code\":\"unauthorized\",\"message\":\"Key not found for 20240001@syuin.ac.kr\"}");
        assertThatThrownBy(() -> sender(Duration.ofSeconds(5)).send(message()))
            .isInstanceOf(MailSendException.class)
            .hasMessageContaining("HTTP 401").hasMessageContaining("unauthorized")
            .hasMessageNotContaining(KEY).hasMessageNotContaining("20240001@syuin.ac.kr");

        brevo.answer(500, "not json");
        assertThatThrownBy(() -> sender(Duration.ofSeconds(5)).send(message()))
            .isInstanceOf(MailSendException.class).hasMessageContaining("HTTP 500");
    }

    @Test void silentServerFailsWithinTheTimeout() {
        brevo.goSilent();
        long started = System.nanoTime();
        assertThatThrownBy(() -> sender(Duration.ofSeconds(1)).send(message())).isInstanceOf(MailSendException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test void unreachableServerFailsAsMailSendException() {
        String baseUrl = brevo.baseUrl();
        brevo.close();
        BrevoMailSender sender = new BrevoMailSender(json, baseUrl, KEY, "sender@example.com", "", Duration.ofSeconds(2));
        assertThatThrownBy(() -> sender.send(message())).isInstanceOf(MailSendException.class);
    }

    @Test void missingSenderAddressFailsAtStartup() {
        assertThatThrownBy(() -> new BrevoMailSender(json, brevo.baseUrl(), KEY, " ", "", Duration.ofSeconds(5)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
    }
}
