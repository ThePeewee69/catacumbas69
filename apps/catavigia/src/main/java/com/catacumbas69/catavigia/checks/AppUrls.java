package com.catacumbas69.catavigia.checks;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.config.CatavigiaProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Where the watched apps answer (orch.md decision 78). Cataponte, Cataudit and Catametricas: the URL set in
 * Catapulsar's Settings (settings topic) wins over {@code catavigia.apps.*}; Catapulsar: {@code CATAPULSAR_URL}.
 * Read on every call, so a new URL applies from the next round.
 */
@Component
public class AppUrls {

    /** Settings-topic key of Cataponte's URL (owned by Catapulsar). */
    public static final String CATAPONTE_KEY = "catapulsar.cataponte-url";
    /** Settings-topic key of Catametricas' URL. */
    public static final String CATAMETRICAS_KEY = "catapulsar.catametricas-url";
    /** Settings-topic key of Cataudit's URL. */
    public static final String CATAUDIT_KEY = "catapulsar.cataudit-url";

    private final CatavigiaProperties.Apps apps;
    private final SharedSettings settings;

    /**
     * Creates the resolver.
     *
     * @param properties application properties, for the fallbacks
     * @param settings the settings topic, if available
     */
    public AppUrls(CatavigiaProperties properties, ObjectProvider<SharedSettings> settings) {
        this.apps = properties.apps();
        this.settings = settings.getIfAvailable();
    }

    /**
     * Returns Catapulsar's base URL.
     *
     * @return the URL, without a trailing slash
     */
    public String catapulsar() {
        return strip(apps.catapulsarUrl());
    }

    /**
     * Returns Cataponte's base URL.
     *
     * @return the URL in use, without a trailing slash
     */
    public String cataponte() {
        return url(CATAPONTE_KEY, apps.cataponteUrl());
    }

    /**
     * Returns Catametricas' base URL.
     *
     * @return the URL in use, without a trailing slash
     */
    public String catametricas() {
        return url(CATAMETRICAS_KEY, apps.catametricasUrl());
    }

    /**
     * Returns Cataudit's base URL.
     *
     * @return the URL in use, without a trailing slash
     */
    public String cataudit() {
        return url(CATAUDIT_KEY, apps.catauditUrl());
    }

    /**
     * Returns every watched app with its URL.
     *
     * @return app name to base URL, in start order
     */
    public Map<String, String> all() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("cataponte", cataponte());
        all.put("cataudit", cataudit());
        all.put("catametricas", catametricas());
        all.put("catapulsar", catapulsar());
        return all;
    }

    private String url(String key, String fallback) {
        if (settings != null) {
            String value = settings.get(key).orElse(null);
            if (StringUtils.hasText(value)) {
                return strip(value);
            }
        }
        return strip(fallback);
    }

    private static String strip(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
