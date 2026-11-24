package com.catacumbas69.catasonarr.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;

/**
 * Two ways in (orch.md decision 109):
 * <ul>
 *   <li>Sonarr's API v3 ({@code /api/v3/**}) with Sonarr's API key, in {@code X-Api-Key} or {@code ?apikey=}, as
 *       Prowlarr, Bazarr, Overseerr, Jellyseerr and Homarr send it: no session, no CSRF, {@code 401} with a wrong
 *       key. A request to {@code /api/v3} without a key goes to the second chain: the page uses the API v3 with its
 *       Dex session and the CSRF token, as Sonarr's own page uses it (orch.md decision 112);</li>
 *   <li>everything else is the page and Catasonarr's own API, with the login through Dex as in Catapulsar (SEC-2):
 *       {@code /api/**} answers {@code 401}, pages go to Dex, changes need the CSRF token from the
 *       {@code XSRF-TOKEN} cookie, and signing out ends Dex's session too. The health probes, the Prometheus scrape
 *       and the API docs need no login.</li>
 * </ul>
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

    /**
     * Sonarr's API v3 called with a key: the first chain. Without a key it is the page's, with its session. The path
     * in any case, as the controllers match it (WebConfig, decision 117).
     */
    private static final RequestMatcher API_KEY = request ->
            startsWithIgnoreCase(request.getRequestURI(), request.getContextPath() + "/api/v3/")
                    && (StringUtils.hasText(request.getHeader(ApiKeyAuthenticationFilter.HEADER))
                    || StringUtils.hasText(request.getParameter(ApiKeyAuthenticationFilter.PARAMETER)));

    /** Catasonarr's own JSON API: without a session it answers 401, where a page would be sent to Dex. */
    private static final RequestMatcher API =
            request -> startsWithIgnoreCase(request.getRequestURI(), request.getContextPath() + "/api/");

    /**
     * Sonarr's API v3 called with a key. Checked first.
     *
     * @param http the builder
     * @param auth the API key
     * @return the chain
     * @throws Exception if the configuration is invalid
     */
    @Bean
    @Order(1)
    public SecurityFilterChain sonarrApiChain(HttpSecurity http, AuthProperties auth) throws Exception {
        if (!StringUtils.hasText(auth.apiKey())) {
            throw new IllegalStateException("catasonarr.auth.api-key is empty: set CATASONARR_AUTH_API_KEY"
                    + " (Secret catasonarr-api-key), or run with the profile no-auth");
        }
        http
                .securityMatcher(API_KEY)
                .authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(auth.apiKey()), AnonymousAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The key is in a header or the URL, never in a cookie: nothing for CSRF to protect
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * The page and Catasonarr's own API, with the login through Dex.
     *
     * @param http the builder
     * @param registrations Dex's registration, for the logout
     * @return the chain
     * @throws Exception if the configuration is invalid
     */
    @Bean
    @Order(2)
    public SecurityFilterChain pageChain(HttpSecurity http, ClientRegistrationRepository registrations)
            throws Exception {
        // The token in a readable cookie, checked as it is (no per-request masking): the page sends it back in a
        // header, or in the _csrf field of the logout form. A null attribute name loads it on every request, so
        // the cookie is always there for the page to read
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri("{baseUrl}/");
        http
                .authorizeHttpRequests(authz -> authz
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
     * Catasonarr's client in Dex. The endpoints are written out instead of discovered, so Catasonarr starts even
     * when Dex is down (RES-1); Dex's issuer is still checked in every ID token.
     *
     * @param auth issuer, client id and secret
     * @return the repository with the one registration
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(AuthProperties auth) {
        if (!StringUtils.hasText(auth.clientSecret())) {
            throw new IllegalStateException("catasonarr.auth.client-secret is empty: set CATASONARR_AUTH_CLIENT_SECRET"
                    + " (Secret catasonarr-oidc), or run with the profile no-auth");
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
                // Where Sign out sends the browser to end Dex's session too
                .providerConfigurationMetadata(Map.of("end_session_endpoint", issuer + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(dex);
    }

    private static boolean startsWithIgnoreCase(String uri, String prefix) {
        return uri.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
