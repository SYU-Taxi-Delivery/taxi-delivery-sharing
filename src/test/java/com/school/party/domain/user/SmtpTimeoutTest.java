package com.school.party.domain.user;

import com.school.party.domain.community.TestClockConfiguration;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
 * Issue #31: an SMTP server that accepts the connection but never answers must not hang the
 * request. Uses the real JavaMailSender with the timeouts from application.properties.
 */
@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class,
    properties = "spring.datasource.url=jdbc:h2:mem:smtp-timeout;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(TestClockConfiguration.class)
class SmtpTimeoutTest {
    private static final ServerSocket SILENT_SMTP = silentServer();
    private static final List<Socket> ACCEPTED = new CopyOnWriteArrayList<>();

    @DynamicPropertySource static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", SILENT_SMTP::getLocalPort);
    }

    @Autowired MockMvc mvc;

    @Test void silentSmtpServerFailsFastAndDoesNotBlockARetry() throws Exception {
        String body = "{\"schoolEmail\":\"20249999@syuin.ac.kr\"}";
        long started = System.nanoTime();
        mvc.perform(post("/api/auth/school-email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MAIL_DELIVERY_FAILED"));
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        // Read timeout is 5 s; without it the request would wait forever.
        assertThat(took).isLessThan(Duration.ofSeconds(15));
        assertThat(ACCEPTED).isNotEmpty();
        // The undelivered code was discarded, so an immediate retry is not throttled (429).
        mvc.perform(post("/api/auth/school-email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("MAIL_DELIVERY_FAILED"));
    }

    @AfterAll static void close() throws IOException {
        for (Socket socket : ACCEPTED) socket.close();
        SILENT_SMTP.close();
    }

    /** Accepts connections and never writes the SMTP greeting. */
    private static ServerSocket silentServer() {
        try {
            ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try { ACCEPTED.add(server.accept()); } catch (IOException e) { return; }
                }
            }, "silent-smtp");
            acceptor.setDaemon(true);
            acceptor.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
