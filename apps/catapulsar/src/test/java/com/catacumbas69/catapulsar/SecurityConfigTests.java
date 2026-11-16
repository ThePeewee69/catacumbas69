package com.catacumbas69.catapulsar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SEC-2 with the login on (orch.md decision 100); every other Spring test runs with the profile {@code no-auth}.
 * The whole context, without MongoDB, Pulsar or the other apps running, as in {@link CatapulsarApplicationTests}.
 */
@SpringBootTest(properties = {
        // Not no-auth (src/test/resources/config/application.properties): SecurityConfig is on
        "spring.profiles.active=login",
        "catapulsar.auth.issuer=https://auth.test",
        "catapulsar.auth.client-secret=test-secret",
        "PULSAR_SERVICE_URL=http://127.0.0.1:1",
        "PULSAR_BROKER_URL=pulsar://127.0.0.1:1",
        "CATAPONTE_URL=http://127.0.0.1:1",
        "CATAUDIT_URL=http://127.0.0.1:1",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/test",
        "logging.level.org.mongodb.driver=ERROR"
})
@AutoConfigureMockMvc
// Spring Boot tests leave metrics export off, so /actuator/prometheus would not exist at all
@AutoConfigureObservability
class SecurityConfigTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Test
    void api_withoutLogin_is401_notARedirect() throws Exception {
        mvc.perform(get("/api/v1/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void page_withoutLogin_goesToDex() throws Exception {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/dex"));
        mvc.perform(get("/oauth2/authorization/dex"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .startsWith("https://auth.test/auth?").contains("client_id=catapulsar"));
    }

    @Test
    void probesScrapeAndApiDocs_needNoLogin() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void session_namesTheUserByTheirPreferredUsername() throws Exception {
        mvc.perform(get("/api/v1/session").with(peewee69()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value("peewee69"))
                .andExpect(jsonPath("$.login").value(true));
    }

    @Test
    void change_withoutTheCsrfToken_is403() throws Exception {
        mvc.perform(post("/api/v1/bridges").with(peewee69())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void change_withTheCsrfTokenInTheHeader_passesSecurity() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/bridges").with(peewee69()).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        // Whatever Catapulsar then answers (Cataponte is not running), it is not Spring Security refusing it
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void signOut_endsDexsSessionToo() throws Exception {
        mvc.perform(post("/logout").with(peewee69()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .startsWith("https://auth.test/logout?id_token_hint=")
                        .contains("post_logout_redirect_uri=http://localhost/"));
    }

    /** A user signed in through Dex, named by preferred_username as SecurityConfig sets it. */
    private OidcLoginRequestPostProcessor peewee69() {
        OidcIdToken token = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", "CiRmY2U3NTdiZC1sb2NhbA", "iss", "https://auth.test", "preferred_username", "peewee69"));
        return oidcLogin().clientRegistration(registrations.findByRegistrationId("dex")).oidcUser(new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "preferred_username"));
    }
}
