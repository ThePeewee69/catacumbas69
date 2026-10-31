package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * The Cataponte base URL used by the bridge backend-for-frontend.
 *
 * <p>Starts with {@code catapulsar.cataponte.url} ({@code CATAPONTE_URL}) and can be changed from the UI. A
 * changed URL is stored in MongoDB ({@code pulsario_settings}) and loaded again at start-up, in the
 * background so that a missing MongoDB does not delay start-up (RES-1). Every change is audited and
 * published on the settings topic (ADR 0011).
 */
@Service
public class CataponteEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(CataponteEndpoint.class);
    private static final int MAX_LENGTH = 2048;

    private final String defaultUrl;
    private final SettingRepository settings;
    private final AuditService audit;
    private final SettingsPublisher publisher;
    private final Clock clock;
    private final Object lock = new Object();
    private volatile String url;
    private volatile boolean changedSinceStart;

    /**
     * Creates the endpoint holder.
     *
     * @param properties application properties, for the default URL
     * @param settings stored settings
     * @param audit audit log
     * @param publisher publishes the URL on the settings topic
     * @param clock time source
     */
    public CataponteEndpoint(CatapulsarProperties properties, SettingRepository settings, AuditService audit,
                             SettingsPublisher publisher, Clock clock) {
        this.defaultUrl = normalize(properties.cataponte().url());
        this.url = defaultUrl;
        this.settings = settings;
        this.audit = audit;
        this.publisher = publisher;
        this.clock = clock;
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

    /** Loads a saved URL in the background once the application has started. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadSavedInBackground() {
        Thread.ofVirtual().name("cataponte-url-loader").start(this::loadSaved);
    }

    /** Applies the saved URL, unless the UI changed it in the meantime. Never throws. */
    void loadSaved() {
        Optional<Setting> saved;
        try {
            saved = settings.findById(Setting.CATAPONTE_URL);
        } catch (RuntimeException e) {
            LOG.warn("Saved Cataponte URL not loaded, using {}: {}", defaultUrl, e.getMessage());
            return;
        }
        saved.ifPresent(setting -> {
            synchronized (lock) {
                if (!changedSinceStart) {
                    url = normalize(setting.getValue());
                    LOG.info("Using saved Cataponte URL {}", url);
                }
            }
        });
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
                settings.deleteById(Setting.CATAPONTE_URL);
            } else {
                settings.save(new Setting(Setting.CATAPONTE_URL, normalized, clock.instant(), user));
            }
            url = normalized;
            changedSinceStart = true;
            audit.record(user, AuditAction.CHANGE_SETTING, null,
                    Map.of("setting", Setting.CATAPONTE_URL, "from", previous, "to", normalized));
            publisher.changed(Setting.CATAPONTE_URL, normalized.equals(defaultUrl) ? null : normalized);
            LOG.info("Cataponte URL changed from {} to {}", previous, normalized);
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
    static String validate(String candidate) {
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
            throw new InvalidSettingException("url", "must include a host, e.g. http://cataponte:8082");
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
