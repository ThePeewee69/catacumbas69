package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The base URL of another service, with a default from configuration that can be changed from the UI.
 *
 * <p>The URL in use comes from the settings topic (ADR 0011) through {@link SharedSettings}, so every replica
 * follows a change within seconds; until the topic has been read the default is used, so start-up never waits
 * (RES-1). A change is stored in {@code catapulsar_settings} (the source Catapulsar republishes from at
 * start-up), applied at once in this replica, audited and published on the topic.
 */
public abstract class UrlSetting {

    private static final Logger LOG = LoggerFactory.getLogger(UrlSetting.class);
    private static final int MAX_LENGTH = 2048;

    private final String id;
    private final String service;
    private final String example;
    private final String defaultUrl;
    private final SettingRepository settings;
    private final AuditService audit;
    private final SettingsPublisher publisher;
    private final Clock clock;
    private final Object lock = new Object();
    private volatile String url;

    /**
     * Creates the setting.
     *
     * @param id setting id (its key on the settings topic)
     * @param service name of the service, for logs
     * @param example example URL shown in validation messages
     * @param defaultUrl URL from configuration
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the URL on the settings topic
     * @param shared values read from the settings topic
     * @param clock time source
     */
    protected UrlSetting(String id, String service, String example, String defaultUrl, SettingRepository settings,
                         AuditService audit, SettingsPublisher publisher, SharedSettings shared, Clock clock) {
        this.id = id;
        this.service = service;
        this.example = example;
        this.defaultUrl = normalize(defaultUrl);
        this.url = this.defaultUrl;
        this.settings = settings;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
        shared.listen(id, this::applyShared);
    }

    /**
     * Returns the URL calls go to now.
     *
     * @return base URL without a trailing slash
     */
    public String url() {
        return url;
    }

    /**
     * Returns the URL from configuration.
     *
     * @return default base URL
     */
    public String defaultUrl() {
        return defaultUrl;
    }

    /**
     * Tells whether the URL was changed from the UI.
     *
     * @return {@code true} if a saved URL is in use
     */
    public boolean isCustom() {
        return !url.equals(defaultUrl);
    }

    /** Applies a value read from the settings topic; an empty or invalid one means the default. */
    private void applyShared(String value) {
        String next = defaultUrl;
        if (value != null) {
            try {
                next = validate(value);
            } catch (InvalidSettingException e) {
                LOG.warn("Ignoring {} URL '{}' from the settings topic: {}", service, value, e.getMessage());
            }
        }
        synchronized (lock) {
            if (!next.equals(url)) {
                url = next;
                LOG.info("{} URL is now {}", service, url);
            }
        }
    }

    /**
     * Validates, stores and applies a new URL.
     *
     * @param newUrl absolute {@code http} or {@code https} URL
     * @param user client IP address, for the audit log
     * @return the URL now in use
     * @throws InvalidSettingException if the URL is not valid
     */
    public String change(String newUrl, String user) {
        String normalized = validate(newUrl);
        synchronized (lock) {
            String previous = url;
            if (normalized.equals(defaultUrl)) {
                settings.deleteById(id);
            } else {
                settings.save(new Setting(id, normalized, clock.instant(), user));
            }
            url = normalized;
            audit.record(user, AuditAction.CHANGE_SETTING, null,
                    Map.of("setting", id, "from", previous, "to", normalized));
            publisher.changed(id, normalized.equals(defaultUrl) ? null : normalized);
            LOG.info("{} URL changed from {} to {}", service, previous, normalized);
            return normalized;
        }
    }

    /**
     * Forgets the saved URL and goes back to the configured one.
     *
     * @param user client IP address, for the audit log
     * @return the URL now in use
     */
    public String reset(String user) {
        return change(defaultUrl, user);
    }

    /** Accepts absolute http(s) URLs with a host and no query, fragment or credentials. */
    private String validate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new InvalidSettingException("url", "must not be blank");
        }
        if (candidate.length() > MAX_LENGTH) {
            throw new InvalidSettingException("url", "must be at most " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(candidate.strip());
        } catch (URISyntaxException e) {
            throw new InvalidSettingException("url", "is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidSettingException("url", "must start with http:// or https://");
        }
        if (uri.getHost() == null) {
            throw new InvalidSettingException("url", "must include a host, e.g. " + example);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new InvalidSettingException("url", "must not contain a query, fragment or credentials");
        }
        return normalize(uri.toString());
    }

    private static String normalize(String value) {
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
