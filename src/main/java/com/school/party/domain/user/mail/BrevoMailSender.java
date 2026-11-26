package com.school.party.domain.user.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends mail through Brevo's HTTPS API (port 443) instead of SMTP. Render's free web services
 * block outbound SMTP ports 25, 465 and 587, so the SMTP sender cannot connect there (#51).
 *
 * <p>Only plain-text {@link SimpleMailMessage} is supported, which is all the auth services send.
 * Failures are reported as {@link MailSendException}, so callers keep their existing handling
 * (discard the issued code, answer 503 MAIL_DELIVERY_FAILED).
 */
public class BrevoMailSender implements JavaMailSender {
    static final String SEND_PATH = "/v3/smtp/email";

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final Duration timeout;

    public BrevoMailSender(ObjectMapper json, String baseUrl, String apiKey, String fromAddress, String fromName, Duration timeout) {
        if (!StringUtils.hasText(apiKey)) throw new IllegalArgumentException("Brevo API key is required");
        if (!StringUtils.hasText(fromAddress)) throw new IllegalStateException("MAIL_FROM must be set when BREVO_API_KEY is set");
        this.json = json;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + SEND_PATH);
        this.apiKey = apiKey.trim();
        this.fromAddress = fromAddress.trim();
        this.fromName = fromName;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public void send(SimpleMailMessage... messages) {
        for (SimpleMailMessage message : messages) sendOne(message);
    }

    private void sendOne(SimpleMailMessage message) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("api-key", apiKey)
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(message), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MailSendException("Brevo API request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Brevo API request interrupted", e);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            // Only the status and Brevo's error code: the message text may echo addresses.
            throw new MailSendException("Brevo API rejected the message: HTTP " + status + errorCode(response.body()));
        }
    }

    private String body(SimpleMailMessage message) {
        String[] to = message.getTo();
        if (to == null || to.length == 0) throw new MailSendException("Mail has no recipient");
        Map<String, Object> sender = new LinkedHashMap<>();
        if (StringUtils.hasText(fromName)) sender.put("name", fromName);
        sender.put("email", StringUtils.hasText(message.getFrom()) ? message.getFrom() : fromAddress);
        List<Map<String, String>> recipients = Arrays.stream(to).map(address -> Map.of("email", address)).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", sender);
        body.put("to", recipients);
        body.put("subject", message.getSubject());
        body.put("textContent", message.getText());
        return json.writeValueAsString(body);
    }

    private String errorCode(String responseBody) {
        try {
            JsonNode code = json.readTree(responseBody).path("code");
            return code.isString() ? " (" + code.asString() + ")" : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        throw new UnsupportedOperationException("BrevoMailSender sends SimpleMailMessage only");
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        throw new UnsupportedOperationException("BrevoMailSender sends SimpleMailMessage only");
    }
}
