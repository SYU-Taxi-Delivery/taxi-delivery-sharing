package com.school.party.domain.user;

import com.school.party.domain.community.MutableClock;
import com.school.party.domain.community.domain.Post;
import com.school.party.domain.community.repository.ParticipationRepository;
import com.school.party.domain.community.repository.PostRepository;
import com.school.party.domain.community.service.PostLifecycleService;
import com.school.party.domain.community.api.ApiException;
import com.school.party.domain.community.api.ErrorCode;
import com.school.party.domain.user.dto.LocalLoginRequest;
import com.school.party.domain.user.entity.LocalCredential;
import com.school.party.domain.user.entity.User;
import com.school.party.domain.user.enums.Role;
import com.school.party.domain.user.enums.UserStatus;
import com.school.party.domain.user.repository.LocalCredentialRepository;
import com.school.party.domain.user.repository.UserAuthRepository;
import com.school.party.domain.user.repository.UserRepository;
import com.school.party.domain.user.service.LocalAuthService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Week-5 authentication contract. The same cases run on H2 (AuthIntegrationTest) and on a
 * disposable MySQL 8.4 container migrated by Flyway (AuthMySqlIT).
 */
public abstract class AuthContract {
    protected static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    protected static final String PASSWORD = "Correct-pass1";
    private static final Pattern CODE = Pattern.compile("\\[(\\d{6})]");

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected UserRepository users;
    @Autowired protected UserAuthRepository auths;
    @Autowired protected LocalCredentialRepository credentials;
    @Autowired protected PostRepository posts;
    @Autowired protected ParticipationRepository participations;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected LocalAuthService localAuth;
    @Autowired protected PostLifecycleService lifecycle;
    @MockitoBean protected JavaMailSender mail;
    @MockitoSpyBean protected LocalCredentialRepository credentialSpy;

    @BeforeEach void arrange() { clean(); clock.set(NOW); reset(mail); }
    @AfterEach void cleanUp() { clean(); }

    private void clean() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch();
        credentials.deleteAllInBatch(); auths.deleteAllInBatch(); users.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- helpers
    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body instanceof String s ? s : mapper.writeValueAsString(body)));
    }

    protected ResultActions sendSignupCode(String email) throws Exception {
        return postJson("/api/auth/school-email/send-code", Map.of("schoolEmail", email));
    }

    protected ResultActions verifySignupCode(String email, String code) throws Exception {
        return postJson("/api/auth/school-email/verify-code", Map.of("schoolEmail", email, "code", code));
    }

    protected ResultActions register(String studentNo, String email, String password) throws Exception {
        return postJson("/api/auth/school-email/register",
            Map.of("studentNo", studentNo, "name", "학생" + studentNo, "schoolEmail", email, "password", password));
    }

    protected ResultActions login(String identifier, String password) throws Exception {
        return postJson("/api/auth/local/login", Map.of("identifier", identifier, "password", password));
    }

    /** Returns the code contained in the most recent message sent to the address. */
    protected String lastCode(String email) {
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeast(1)).send(captor.capture());
        List<SimpleMailMessage> sent = captor.getAllValues();
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).getTo() != null && List.of(sent.get(i).getTo()).contains(email)) {
                Matcher m = CODE.matcher(sent.get(i).getText());
                assertThat(m.find()).as("mail contains a 6 digit code").isTrue();
                return m.group(1);
            }
        }
        throw new AssertionError("No mail was sent to " + email);
    }

    protected static String wrong(String code) { return code.equals("000000") ? "111111" : "000000"; }

    protected long verifiedSignup(String email) throws Exception {
        sendSignupCode(email).andExpect(status().isOk());
        verifySignupCode(email, lastCode(email)).andExpect(status().isOk());
        return 0;
    }

    protected User registered(String studentNo, String email) throws Exception {
        verifiedSignup(email);
        register(studentNo, email, PASSWORD).andExpect(status().isOk());
        return users.findByStudentNo(studentNo).orElseThrow();
    }

    protected User account(String studentNo, Role role, UserStatus status) {
        User user = users.save(User.builder().studentNo(studentNo).name("계정" + studentNo)
            .schoolEmail(studentNo + "@syuin.ac.kr").role(role).status(status).build());
        credentials.save(LocalCredential.builder().user(user).passwordHash(encoder.encode(PASSWORD)).build());
        return user;
    }

    protected LocalCredential credential(User user) { return credentials.findByUserId(user.getId()).orElseThrow(); }

    protected int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    protected void advance(Duration duration) { clock.set(clock.instant().plus(duration)); }

    protected static List<String> compete(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> task : tasks) futures.add(pool.submit(() -> {
                ready.countDown(); start.await(); return task.call();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally { pool.shutdownNow(); }
    }

    protected String outcome(ResultActions result) throws Exception {
        var response = result.andReturn().getResponse();
        if (response.getStatus() == 200) return "OK";
        return response.getStatus() + ":" + mapper.readTree(response.getContentAsString()).path("error").path("code").asText();
    }

    protected String httpAttempt(Callable<ResultActions> action) {
        try { return outcome(action.call()); }
        catch (Exception e) { return "EXCEPTION:" + e.getClass().getSimpleName(); }
    }

    // ------------------------------------------------- school email verification
    @Test void EMAIL01_onlySchoolDomainsReceiveCodesAndInvalidInputIs400() throws Exception {
        for (String email : List.of("student@gmail.com", "student@syu.ac.kr.evil.com", "student@mail.syuin.ac.kr", "a@b@syuin.ac.kr")) {
            sendSignupCode(email).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").exists());
        }
        postJson("/api/auth/school-email/send-code", "{}").andExpect(status().isBadRequest());
        postJson("/api/auth/school-email/send-code", "{\"schoolEmail\":\"x@syuin.ac.kr\",\"extra\":1}").andExpect(status().isBadRequest());
        verify(mail, never()).send(any(SimpleMailMessage.class));
        sendSignupCode("20240001@syuin.ac.kr").andExpect(status().isOk());
        sendSignupCode("staff01@syu.ac.kr").andExpect(status().isOk());
    }

    @Test void EMAIL02_addressIsNormalizedSoCaseVariantsShareOneAccount() throws Exception {
        sendSignupCode("  Mixed.Case@SYUIN.AC.KR ").andExpect(status().isOk());
        verifySignupCode("mixed.case@syuin.ac.kr", lastCode("mixed.case@syuin.ac.kr")).andExpect(status().isOk());
        register("20240002", "MIXED.case@syuin.ac.kr", PASSWORD).andExpect(status().isOk());
        assertThat(users.findBySchoolEmail("mixed.case@syuin.ac.kr")).isPresent();
        sendSignupCode("MIXED.CASE@syuin.ac.kr").andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
        login("Mixed.Case@SYUIN.ac.kr", PASSWORD).andExpect(status().isOk());
    }

    @Test void EMAIL03_codeExpiresAfterFiveMinutes() throws Exception {
        String email = "20240003@syuin.ac.kr";
        sendSignupCode(email).andExpect(status().isOk());
        String code = lastCode(email);
        advance(Duration.ofMinutes(5).plusSeconds(1));
        verifySignupCode(email, code).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_EXPIRED"));
        register("20240003", email, PASSWORD).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
        assertThat(count("users")).isZero();
    }

    @Test void EMAIL04_fiveWrongCodesInvalidateTheCode() throws Exception {
        String email = "20240004@syuin.ac.kr";
        sendSignupCode(email).andExpect(status().isOk());
        String code = lastCode(email);
        for (int i = 1; i <= 4; i++) verifySignupCode(email, wrong(code)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_MISMATCH"));
        verifySignupCode(email, wrong(code)).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("VERIFICATION_ATTEMPTS_EXCEEDED"));
        verifySignupCode(email, code).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_EXPIRED"));
    }

    @Test void EMAIL05_resendIsThrottledAndReplacesThePreviousCode() throws Exception {
        String email = "20240005@syuin.ac.kr";
        sendSignupCode(email).andExpect(status().isOk());
        String first = lastCode(email);
        sendSignupCode(email).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("VERIFICATION_RESEND_TOO_SOON"));
        advance(Duration.ofSeconds(60));
        sendSignupCode(email).andExpect(status().isOk());
        String second = lastCode(email);
        if (!first.equals(second)) verifySignupCode(email, first).andExpect(status().isBadRequest());
        verifySignupCode(email, second).andExpect(status().isOk());
    }

    @Test void EMAIL06_mailFailureIsReportedAndDoesNotBlockRetry() throws Exception {
        String email = "20240006@syuin.ac.kr";
        doThrow(new MailSendException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        sendSignupCode(email).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("MAIL_DELIVERY_FAILED"));
        reset(mail);
        sendSignupCode(email).andExpect(status().isOk());
        verifySignupCode(email, lastCode(email)).andExpect(status().isOk());
    }

    @Test void EMAIL07_verificationIsSingleUseAndExpires() throws Exception {
        String email = "20240007@syuin.ac.kr";
        verifiedSignup(email);
        advance(Duration.ofMinutes(30).plusSeconds(1));
        register("20240007", email, PASSWORD).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
        verifiedSignup(email);
        register("20240007", email, PASSWORD).andExpect(status().isOk());
        assertThat(count("users")).isEqualTo(1);
    }

    // ------------------------------------------------------------ registration
    @Test void JOIN01_registrationLinksUsersUserAuthAndLocalCredential() throws Exception {
        String email = "20240010@syuin.ac.kr";
        verifiedSignup(email);
        String body = register("20240010", email, PASSWORD).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.trim());
        var row = jdbc.queryForMap("select u.id, u.role, u.status, a.user_id auth_user, a.provider_user_id, "
            + "c.user_id cred_user, c.password_hash, c.failed_login_count, c.locked_until "
            + "from users u join user_auth a on a.user_id = u.id join local_credential c on c.user_id = u.id where u.id = ?", id);
        assertThat(row.get("ROLE") == null ? row.get("role") : row.get("ROLE")).isEqualTo("STUDENT");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("user_auth")).isEqualTo(1);
        assertThat(count("local_credential")).isEqualTo(1);
        String hash = String.valueOf(row.getOrDefault("password_hash", row.get("PASSWORD_HASH")));
        assertThat(hash).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        login("20240010", PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(String.valueOf(id)));
    }

    @Test void JOIN02_failureWhileSavingCredentialLeavesNoPartialRowsAndAllowsRetry() throws Exception {
        String email = "20240011@syuin.ac.kr";
        verifiedSignup(email);
        doThrow(new DataIntegrityViolationException("simulated credential failure")).when(credentialSpy).save(any());
        var failed = register("20240011", email, PASSWORD).andReturn().getResponse();
        assertThat(failed.getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(count("users")).isZero();
        assertThat(count("user_auth")).isZero();
        assertThat(count("local_credential")).isZero();
        reset(credentialSpy);
        register("20240011", email, PASSWORD).andExpect(status().isOk());
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("local_credential")).isEqualTo(1);
    }

    @Test void JOIN03_duplicateStudentNumberIsConflictWithoutPartialRows() throws Exception {
        registered("20240012", "first12@syuin.ac.kr");
        verifiedSignup("second12@syuin.ac.kr");
        register("20240012", "second12@syuin.ac.kr", PASSWORD).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("STUDENT_NO_ALREADY_REGISTERED"));
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("user_auth")).isEqualTo(1);
        assertThat(count("local_credential")).isEqualTo(1);
    }

    @Test void JOIN04_clientCannotChooseRoleAndWeakPasswordsAreRejected() throws Exception {
        String email = "20240013@syuin.ac.kr";
        verifiedSignup(email);
        postJson("/api/auth/school-email/register", Map.of("studentNo", "20240013", "name", "권한위조",
            "schoolEmail", email, "password", PASSWORD, "role", "ADMIN")).andExpect(status().isBadRequest());
        register("20240013", email, "short").andExpect(status().isBadRequest());
        register("20240013", email, "가".repeat(30)).andExpect(status().isBadRequest());
        assertThat(count("users")).isZero();
        register("20240013", email, PASSWORD).andExpect(status().isOk());
        assertThat(users.findByStudentNo("20240013").orElseThrow().getRole()).isEqualTo(Role.STUDENT);
    }

    // ------------------------------------------------------------- local login
    @Test void LOGIN01_fifthConsecutiveFailureLocksForThirtyMinutes() throws Exception {
        User user = account("20240020", Role.STUDENT, UserStatus.ACTIVE);
        for (int i = 1; i <= 4; i++) {
            login("20240020", "wrong-password").andExpect(status().isUnauthorized());
            assertThat(credential(user).getFailedLoginCount()).isEqualTo(i);
            assertThat(credential(user).getLockedUntil()).isNull();
        }
        login("20240020", "wrong-password").andExpect(status().isUnauthorized());
        assertThat(credential(user).getFailedLoginCount()).isEqualTo(5);
        assertThat(credential(user).getLockedUntil()).isNotNull();
        // Locked: the correct password is refused and the counter does not move.
        login("20240020", PASSWORD).andExpect(status().isUnauthorized());
        advance(Duration.ofMinutes(29).plusSeconds(59));
        login("20240020", PASSWORD).andExpect(status().isUnauthorized());
        assertThat(credential(user).getFailedLoginCount()).isEqualTo(5);
        advance(Duration.ofSeconds(1));
        login("20240020", PASSWORD).andExpect(status().isOk());
        assertThat(credential(user).getFailedLoginCount()).isZero();
        assertThat(credential(user).getLockedUntil()).isNull();
    }

    @Test void LOGIN02_singleTypoAfterLockExpiryStartsANewCount() throws Exception {
        User user = account("20240021", Role.STUDENT, UserStatus.ACTIVE);
        for (int i = 0; i < 5; i++) login("20240021", "wrong-password").andExpect(status().isUnauthorized());
        advance(Duration.ofMinutes(30));
        login("20240021", "wrong-password").andExpect(status().isUnauthorized());
        assertThat(credential(user).getFailedLoginCount()).isEqualTo(1);
        assertThat(credential(user).getLockedUntil()).isNull();
        login("20240021", PASSWORD).andExpect(status().isOk());
    }

    @Test void LOGIN03_successResetsCounterAndRecordsLoginTime() throws Exception {
        User user = account("20240022", Role.STUDENT, UserStatus.ACTIVE);
        for (int i = 0; i < 4; i++) login("20240022", "wrong-password").andExpect(status().isUnauthorized());
        login("20240022@syuin.ac.kr", PASSWORD).andExpect(status().isOk());
        assertThat(credential(user).getFailedLoginCount()).isZero();
        assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
        login("unknown-user", PASSWORD).andExpect(status().isUnauthorized());
        login("20240022", "").andExpect(status().isBadRequest());
    }

    @Test void LOGIN04_concurrentWrongPasswordsAreCountedWithoutLoss() throws Exception {
        User four = account("20240023", Role.STUDENT, UserStatus.ACTIVE);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) tasks.add(() -> serviceLogin("20240023", "wrong-password"));
        assertThat(compete(tasks)).allMatch(v -> v.equals("REJECTED"));
        assertThat(credential(four).getFailedLoginCount()).isEqualTo(4);
        assertThat(credential(four).getLockedUntil()).isNull();

        User twelve = account("20240024", Role.STUDENT, UserStatus.ACTIVE);
        tasks.clear();
        for (int i = 0; i < 12; i++) tasks.add(() -> serviceLogin("20240024", "wrong-password"));
        assertThat(compete(tasks)).allMatch(v -> v.equals("REJECTED"));
        assertThat(credential(twelve).getFailedLoginCount()).isEqualTo(5);
        assertThat(credential(twelve).getLockedUntil()).isNotNull();
        assertThat(serviceLogin("20240024", PASSWORD)).isEqualTo("REJECTED");
    }

    @Test void LOGIN05_correctPasswordRacingWrongOnesNeverExceedsFiveFailures() throws Exception {
        User user = account("20240025", Role.STUDENT, UserStatus.ACTIVE);
        for (int i = 0; i < 4; i++) login("20240025", "wrong-password").andExpect(status().isUnauthorized());
        List<String> results = compete(List.of(
            () -> serviceLogin("20240025", PASSWORD), () -> serviceLogin("20240025", "wrong-password"),
            () -> serviceLogin("20240025", "wrong-password"), () -> serviceLogin("20240025", "wrong-password")));
        LocalCredential after = credential(user);
        assertThat(after.getFailedLoginCount()).isBetween(0, 5);
        // Either the correct login came first (then at most 3 new failures) or the 5th failure locked it.
        if (after.getLockedUntil() != null) assertThat(after.getFailedLoginCount()).isEqualTo(5);
        else assertThat(results).contains("OK");
    }

    protected String serviceLogin(String identifier, String password) {
        try {
            LocalLoginRequest request = mapper.readValue(mapper.writeValueAsString(
                Map.of("identifier", identifier, "password", password)), LocalLoginRequest.class);
            localAuth.authenticateLocal(request);
            return "OK";
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "REJECTED";
        } catch (RuntimeException e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------- password reset
    @Test void RESET01_resetChangesPasswordAndClearsLock() throws Exception {
        User user = account("20240030", Role.STUDENT, UserStatus.ACTIVE);
        for (int i = 0; i < 5; i++) login("20240030", "wrong-password").andExpect(status().isUnauthorized());
        advance(Duration.ofMinutes(1));
        postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", "20240030@syuin.ac.kr")).andExpect(status().isOk());
        String code = lastCode("20240030@syuin.ac.kr");
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", "20240030@syuin.ac.kr", "code", code,
            "newPassword", "Brand-new-pass2")).andExpect(status().isOk());
        assertThat(credential(user).getFailedLoginCount()).isZero();
        assertThat(credential(user).getLockedUntil()).isNull();
        assertThat(credential(user).getPasswordUpdatedAt())
            .isEqualTo(java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
        login("20240030", PASSWORD).andExpect(status().isUnauthorized());
        login("20240030", "Brand-new-pass2").andExpect(status().isOk());
        // The code is single use.
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", "20240030@syuin.ac.kr", "code", code,
            "newPassword", "Another-pass3")).andExpect(status().isBadRequest());
    }

    @Test void RESET02_unknownOrInactiveAccountsGetTheSameAnswerWithoutMail() throws Exception {
        account("20240031", Role.STUDENT, UserStatus.SUSPENDED);
        String unknown = postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", "nobody@syuin.ac.kr"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String suspended = postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", "20240031@syuin.ac.kr"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(suspended).isEqualTo(unknown);
        verify(mail, never()).send(any(SimpleMailMessage.class));
        postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", "x@gmail.com")).andExpect(status().isBadRequest());
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", "nobody@syuin.ac.kr", "code", "123456",
            "newPassword", "Brand-new-pass2")).andExpect(status().isBadRequest());
    }

    @Test void RESET03_wrongExpiredOrWeakInputsKeepTheOldPassword() throws Exception {
        User user = account("20240032", Role.STUDENT, UserStatus.ACTIVE);
        String email = "20240032@syuin.ac.kr";
        postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", email)).andExpect(status().isOk());
        String code = lastCode(email);
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", email, "code", code, "newPassword", "short"))
            .andExpect(status().isBadRequest());
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", email, "code", wrong(code), "newPassword", "Brand-new-pass2"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_MISMATCH"));
        advance(Duration.ofMinutes(5).plusSeconds(1));
        postJson("/api/auth/password-reset/confirm", Map.of("schoolEmail", email, "code", code, "newPassword", "Brand-new-pass2"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VERIFICATION_CODE_EXPIRED"));
        login("20240032", PASSWORD).andExpect(status().isOk());
        assertThat(encoder.matches(PASSWORD, credential(user).getPasswordHash())).isTrue();
    }

    @Test void RESET04_signupAndResetCodesAreNotInterchangeable() throws Exception {
        account("20240033", Role.STUDENT, UserStatus.ACTIVE);
        String email = "20240033@syuin.ac.kr";
        postJson("/api/auth/password-reset/send-code", Map.of("schoolEmail", email)).andExpect(status().isOk());
        String resetCode = lastCode(email);
        verifySignupCode(email, resetCode).andExpect(status().isBadRequest());
        login("20240033", PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------- roles
    @Test void ROLE01_studentAndAdminReachOnlyTheirFunctions() throws Exception {
        User student = account("20240040", Role.STUDENT, UserStatus.ACTIVE);
        User admin = account("20240041", Role.ADMIN, UserStatus.ACTIVE);
        MockHttpSession studentSession = session("20240040");
        MockHttpSession adminSession = session("20240041");
        mvc.perform(get("/api/admin/role-probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/role-probe").session(studentSession)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN_OPERATION"));
        mvc.perform(get("/api/admin/role-probe").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get("/api/posts").session(studentSession)).andExpect(status().isOk());
        mvc.perform(get("/api/posts").session(adminSession)).andExpect(status().isOk());
        // Service-level admin operation (soft removal) is refused to students.
        Post post = posts.save(Post.taxi(student, 4, NOW.plus(Duration.ofHours(2)), NOW));
        assertThatThrownBy(() -> lifecycle.remove(post.getId(), student.getId()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN_OPERATION));
        lifecycle.remove(post.getId(), admin.getId());
    }

    protected MockHttpSession session(String identifier) throws Exception {
        var result = login(identifier, PASSWORD).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    // ------------------------------------------------------------- concurrency
    @Test void RACE01_concurrentRegistrationWithOneVerificationCreatesOneAccount() throws Exception {
        String email = "20240050@syuin.ac.kr";
        verifiedSignup(email);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String no = "2024005" + i;
            tasks.add(() -> httpAttempt(() -> register(no, email, PASSWORD)));
        }
        List<String> results = compete(tasks);
        assertThat(Collections.frequency(results, "OK")).as(results.toString()).isEqualTo(1);
        assertThat(results).allMatch(v -> v.equals("OK") || v.startsWith("4"));
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("user_auth")).isEqualTo(1);
        assertThat(count("local_credential")).isEqualTo(1);
    }

    @Test void RACE02_concurrentRegistrationOfOneStudentNumberCreatesOneAccount() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String email = "race" + i + "@syuin.ac.kr";
            verifiedSignup(email);
            tasks.add(() -> httpAttempt(() -> register("20240060", email, PASSWORD)));
        }
        List<String> results = compete(tasks);
        assertThat(Collections.frequency(results, "OK")).as(results.toString()).isEqualTo(1);
        assertThat(results).allMatch(v -> v.equals("OK") || v.startsWith("409:"));
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("user_auth")).isEqualTo(1);
        assertThat(count("local_credential")).isEqualTo(1);
    }

    @Test void RACE03_concurrentWrongCodesCannotExceedTheAttemptLimit() throws Exception {
        String email = "20240070@syuin.ac.kr";
        sendSignupCode(email).andExpect(status().isOk());
        String code = lastCode(email);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) tasks.add(() -> httpAttempt(() -> verifySignupCode(email, wrong(code))));
        List<String> results = compete(tasks);
        assertThat(results).noneMatch(v -> v.equals("OK"));
        assertThat(Collections.frequency(results, "400:VERIFICATION_CODE_MISMATCH")).as(results.toString()).isEqualTo(4);
        verifySignupCode(email, code).andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------- DB integrity
    @Test void DB01_foreignKeysRejectOrphanAuthRowsAndUserDeletionWithCredentials() throws Exception {
        assertThatThrownBy(() -> jdbc.update("insert into local_credential(user_id, password_hash, failed_login_count, password_updated_at) "
            + "values (987654, 'x', 0, current_timestamp)")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into user_auth(user_id, provider, provider_user_id, linked_at) "
            + "values (987654, 'LOCAL', 'x', current_timestamp)")).isInstanceOf(DataIntegrityViolationException.class);
        User user = registered("20240080", "20240080@syuin.ac.kr");
        assertThatThrownBy(() -> jdbc.update("delete from users where id = ?", user.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("users")).isEqualTo(1);
        clearInvocations(mail);
    }

    /** Test-only admin endpoint used to check URL-level role rules. Not packaged into the app. */
    @RestController
    public static class RoleProbeController {
        @GetMapping("/api/admin/role-probe") String probe() { return "admin-ok"; }
    }
}
