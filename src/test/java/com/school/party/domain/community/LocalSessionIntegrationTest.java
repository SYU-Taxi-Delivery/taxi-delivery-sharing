package com.school.party.domain.community;

import com.school.party.domain.user.entity.*;
import com.school.party.domain.user.enums.*;
import com.school.party.domain.user.repository.*;
import com.school.party.domain.community.repository.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.syu.taxidelivery.TaxiDeliverySharingApplication.class)
@AutoConfigureMockMvc @ActiveProfiles("test")
class LocalSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired LocalCredentialRepository credentials;
    @Autowired UserAuthRepository auths;
    @Autowired PostRepository posts;
    @Autowired ParticipationRepository participations;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    @MockitoBean JavaMailSender mail;
    @BeforeEach @AfterEach void clean() {
        participations.deleteAllInBatch(); posts.deleteAllInBatch();
        credentials.deleteAllInBatch(); auths.deleteAllInBatch(); users.deleteAllInBatch();
    }
    User account(UserStatus status) {
        User user = users.save(User.builder().studentNo("session-test").name("세션사용자")
            .schoolEmail("session-test@syu.ac.kr").role(Role.STUDENT).status(status).build());
        credentials.save(LocalCredential.builder().user(user).passwordHash(encoder.encode("test-password")).build());
        return user;
    }
    String login(String password) throws Exception {
        return mapper.writeValueAsString(Map.of("identifier", "session-test", "password", password));
    }
    @Test void schoolRegistrationLoginAndPostCreationShareTeamUserId() throws Exception {
        mvc.perform(post("/api/auth/school-email/send-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"schoolEmail\":\"registered@syu.ac.kr\"}")).andExpect(status().isOk());
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String code = message.getValue().getText().split("\\[")[1].substring(0, 6);
        mvc.perform(post("/api/auth/school-email/verify-code").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("schoolEmail", "registered@syu.ac.kr", "code", code)))).andExpect(status().isOk());
        String registered = mvc.perform(post("/api/auth/school-email/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"studentNo\":\"registered\",\"name\":\"등록학생\",\"schoolEmail\":\"registered@syu.ac.kr\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var result = mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"identifier\":\"registered\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("userId").value(registered)).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        String time = clock.instant().plusSeconds(3600).truncatedTo(ChronoUnit.MINUTES).atOffset(java.time.ZoneOffset.UTC).toString();
        var created = mvc.perform(post("/api/posts").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("type", "TAXI", "title", "세션 모집", "content", "만나요", "capacity", 4,
                "referenceTime", time, "departure", "석계역", "destination", "삼육대학교"))))
            .andExpect(status().isCreated()).andExpect(jsonPath("data.currentMemberCount").value(1)).andReturn();
        long id = Long.parseLong(mapper.readTree(created.getResponse().getContentAsString()).path("data").path("postId").asText());
        assertThat(posts.findById(id).orElseThrow().getAuthor().getId()).isEqualTo(Long.parseLong(registered));
        mvc.perform(get("/api/posts").session(session)).andExpect(status().isOk());
    }
    @Test void wrongPasswordsPersistFailureCountAndLockAfterFiveAttempts() throws Exception {
        User user = account(UserStatus.ACTIVE);
        for (int i = 1; i <= 5; i++) {
            mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login("wrong")))
                .andExpect(status().isUnauthorized());
            assertThat(credentials.findByUserId(user.getId()).orElseThrow().getFailedLoginCount()).isEqualTo(i);
        }
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getLockedUntil()).isNotNull();
        mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login("test-password")))
            .andExpect(status().isUnauthorized());
    }
    @Test void csrfIsRequiredForLoginAndAnonymousCsrfEndpointWorks() throws Exception {
        account(UserStatus.ACTIVE);
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("data.token").isNotEmpty());
        mvc.perform(post("/api/auth/local/login").contentType(MediaType.APPLICATION_JSON).content(login("test-password")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("error.code").value("CSRF_INVALID"));
    }
    @Test void inactiveAccountCannotEstablishSession() throws Exception {
        account(UserStatus.SUSPENDED);
        mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login("test-password")))
            .andExpect(status().isUnauthorized());
    }
    @Test void blankCredentialsAndInjectedUserIdAreRejected() throws Exception {
        mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"identifier\":\"session-test\",\"password\":\"test-password\",\"userId\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/posts").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
    }
    @Test void sessionIdChangesOnLoginAndLogoutInvalidatesSession() throws Exception {
        account(UserStatus.ACTIVE);
        var session = new MockHttpSession(); String originalId = session.getId();
        mvc.perform(post("/api/auth/local/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(login("test-password"))).andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(originalId);
        mvc.perform(get("/api/posts").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/posts")).andExpect(status().isUnauthorized());
    }
    @Test void existingSessionCannotAccessApisAfterAccountIsSuspended() throws Exception {
        User user = account(UserStatus.ACTIVE);
        var result = mvc.perform(post("/api/auth/local/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(login("test-password"))).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        users.save(User.builder().id(user.getId()).studentNo(user.getStudentNo()).name(user.getName())
            .schoolEmail(user.getSchoolEmail()).role(user.getRole()).status(UserStatus.SUSPENDED)
            .createdAt(user.getCreatedAt()).updatedAt(user.getUpdatedAt()).build());
        mvc.perform(get("/api/posts").session(session)).andExpect(status().isUnauthorized());
    }
}
