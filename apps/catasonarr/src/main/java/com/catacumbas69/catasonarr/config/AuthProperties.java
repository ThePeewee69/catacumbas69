package com.catacumbas69.catasonarr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login (orch.md decision 109), under {@code catasonarr.auth.*}: Dex for the page (SEC-2), and Sonarr's API key for
 * the API v3. Not used with the profile {@code no-auth}, which turns both off (IntelliJ and the tests).
 *
 * @param issuer Dex's issuer URL, also the base of its endpoints, e.g. {@code https://auth.catacumbas69.com}
 * @param clientId Catasonarr's client id in Dex
 * @param clientSecret its client secret, from the Secret {@code catasonarr-oidc} (never in git)
 * @param apiKey the key the API v3 clients send in {@code X-Api-Key} or {@code ?apikey=}: the one of the Sonarr it
 *               replaces, from the Secret {@code catasonarr-api-key} (never in git)
 */
@ConfigurationProperties(prefix = "catasonarr.auth")
public record AuthProperties(String issuer, String clientId, String clientSecret, String apiKey) {
}
