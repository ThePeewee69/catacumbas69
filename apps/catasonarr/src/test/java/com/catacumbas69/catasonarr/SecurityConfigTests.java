package com.catacumbas69.catasonarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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

/**
 * The two ways in, with the login on (orch.md decisions 109 and 112): Sonarr's API key for /api/v3, Dex for the rest
 * and for the page's own calls to /api/v3. Every other Spring test runs with the profile {@code no-auth}. The whole
 * context, without MongoDB or Pulsar running.
 */
@SpringBootTest(properties = {
        // Not no-auth (src/test/resources/config/application.properties): SecurityConfig is on
        "spring.profiles.active=login",
        "catasonarr.auth.issuer=https://auth.test",
        "catasonarr.auth.client-secret=test-secret",
        "catasonarr.auth.api-key=" + SecurityConfigTests.KEY,
        "PULSAR_SERVICE_URL=http://127.0.0.1:1",
        "PULSAR_BROKER_URL=pulsar://127.0.0.1:1",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/test",
        "logging.level.org.mongodb.driver=ERROR"
})
@AutoConfigureMockMvc
// Spring Boot tests leave metrics export off, so /actuator/prometheus would not exist at all
@AutoConfigureObservability
class SecurityConfigTests {

    static final String KEY = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Test
    void sonarrApi_withTheKey_inTheHeaderOrTheQuery() throws Exception {
        mvc.perform(get("/api/v3/system/status").header("X-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("Catasonarr"));
        mvc.perform(get("/api/v3/system/status").param("apikey", KEY)).andExpect(status().isOk());
    }

    @Test
    void sonarrApi_pathsInAnyCase_asSonarrs() throws Exception {
        mvc.perform(get("/api/v3/System/Status").header("X-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("Catasonarr"));
        mvc.perform(get("/API/V3/system/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void sonarrApi_withoutTheKey_orWithAnother_is401() throws Exception {
        mvc.perform(get("/api/v3/system/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v3/system/status").header("X-Api-Key", "ffffffffffffffffffffffffffffffff"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sonarrApi_fromThePage_withTheDexSession_andTheCsrfTokenForChanges() throws Exception {
        mvc.perform(get("/api/v3/system/status").with(peewee69())).andExpect(status().isOk());
        mvc.perform(post("/api/v3/command").with(peewee69()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"RssSync\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sonarrApi_withAKey_needsNoCsrfToken() throws Exception {
        mvc.perform(post("/api/v3/tag").header("X-Api-Key", "ffffffffffffffffffffffffffffffff")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void page_withoutLogin_goesToDex() throws Exception {
        mvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/oauth2/authorization/dex"));
        mvc.perform(get("/oauth2/authorization/dex"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .startsWith("https://auth.test/auth?").contains("client_id=catasonarr"));
    }

    @Test
    void ownApi_withoutLogin_is401_notARedirect() throws Exception {
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
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
