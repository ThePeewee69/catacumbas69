package com.catacumbas69.catapulsar.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * SEC-2 (orch.md decision 100): every page and API call needs a login through Dex, except the health probes, the
 * Prometheus scrape and the API docs. The API answers {@code 401} instead of redirecting, so the page reloads and
 * goes through Dex; changes need the CSRF token, which the page reads from the {@code XSRF-TOKEN} cookie and sends
 * as {@code X-XSRF-TOKEN}. Logging out ends Dex's session too (RP-initiated logout).
 *
 * <p>The profile {@code no-auth} turns all of this off (IntelliJ and the tests): this class is skipped and
 * {@code application-no-auth.properties} excludes Spring Security's auto-configuration.
 */
@Configuration
@EnableWebSecurity
@Profile("!no-auth")
public class SecurityConfig {

    /** The registration id, also the last segment of the redirect URI registered in Dex. */
    static final String REGISTRATION_ID = "dex";

    /** The JSON API: without a session it answers 401, where a page would be sent to Dex. */
    private static final RequestMatcher API =
            request -> request.getRequestURI().startsWith(request.getContextPath() + "/api/");

    /**
     * The filter chain.
     *
     * @param http the builder
     * @param registrations Dex's registration, for the logout
     * @return the chain
     * @throws Exception if the configuration is invalid
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations)
            throws Exception {
        // The token in a readable cookie, checked as it is (no per-request masking): the page sends it back in a
        // header, or in the _csrf field of the logout form. A null attribute name loads it on every request, so
        // the cookie is always there for the page to read
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), API))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .logout(out -> out.logoutSuccessHandler(logout));
        return http.build();
    }

    /**
     * Catapulsar's client in Dex. The endpoints are written out instead of discovered, so Catapulsar starts even
     * when Dex is down (RES-1); Dex's issuer is still checked in every ID token.
     *
     * @param auth issuer, client id and secret
     * @return the repository with the one registration
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(AuthProperties auth) {
        if (auth.clientSecret() == null || auth.clientSecret().isBlank()) {
            throw new IllegalStateException("catapulsar.auth.client-secret is empty: set CATAPULSAR_AUTH_CLIENT_SECRET"
                    + " (Secret catapulsar-oidc), or run with the profile no-auth");
        }
        String issuer = auth.issuer().replaceAll("/+$", "");
        ClientRegistration dex = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Dex")
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .issuerUri(issuer)
                .authorizationUri(issuer + "/auth")
                .tokenUri(issuer + "/token")
                .jwkSetUri(issuer + "/keys")
                .userInfoUri(issuer + "/userinfo")
                // The user's name, and so the actor of every event: peewee69, not Dex's opaque subject
                .userNameAttributeName("preferred_username")
                // Where the Sair button sends the browser to end Dex's session too
                .providerConfigurationMetadata(Map.of("end_session_endpoint", issuer + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(dex);
    }
}
