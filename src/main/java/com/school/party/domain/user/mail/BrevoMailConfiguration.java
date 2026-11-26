package com.school.party.domain.user.mail;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Uses Brevo's HTTPS API for mail when {@code BREVO_API_KEY} is set (deployment on Render, #51).
 * Without it this bean is not created and Spring Boot's SMTP {@code JavaMailSender} (spring.mail.*)
 * is used as before, which backs off because it only applies when no JavaMailSender bean exists.
 */
@Configuration(proxyBeanMethods = false)
public class BrevoMailConfiguration {

    @Bean
    @Conditional(BrevoApiKeyPresent.class)
    JavaMailSender brevoMailSender(ObjectMapper json,
                                   @Value("${app.mail.brevo.base-url}") String baseUrl,
                                   @Value("${app.mail.brevo.api-key}") String apiKey,
                                   @Value("${app.mail.from}") String from,
                                   @Value("${app.mail.from-name}") String fromName,
                                   @Value("${app.mail.brevo.timeout:5s}") Duration timeout) {
        return new BrevoMailSender(json, baseUrl, apiKey, from, fromName, timeout);
    }

    static class BrevoApiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("app.mail.brevo.api-key"));
        }
    }
}
