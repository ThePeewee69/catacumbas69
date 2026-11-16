package com.catacumbas69.catapulsar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login through Dex (SEC-2, orch.md decision 100), under {@code catapulsar.auth.*}. Not used with the profile
 * {@code no-auth}, which turns the login off (IntelliJ and the tests).
 *
 * @param issuer Dex's issuer URL, also the base of its endpoints, e.g. {@code https://auth.catacumbas69.com}
 * @param clientId Catapulsar's client id in Dex
 * @param clientSecret its client secret, from the Secret {@code catapulsar-oidc} (never in git)
 */
@ConfigurationProperties(prefix = "catapulsar.auth")
public record AuthProperties(String issuer, String clientId, String clientSecret) {
}
