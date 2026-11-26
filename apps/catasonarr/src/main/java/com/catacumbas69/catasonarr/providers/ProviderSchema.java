package com.catacumbas69.catasonarr.providers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fields of Catasonarr's indexers and download clients as Sonarr 4.0.20 describes them (orch.md decision 122),
 * in its order, with its names, labels, types and defaults, so Prowlarr and Sonarr's clients read and write them as
 * Sonarr's. Passwords and API keys are shown as {@link #PRIVATE_VALUE}, and that value sent back keeps the old one.
 */
public final class ProviderSchema {

    /** What a password or an API key is shown as. */
    public static final String PRIVATE_VALUE = "********";

    /**
     * One field.
     *
     * @param name e.g. {@code baseUrl}
     * @param label e.g. {@code URL}
     * @param type {@code textbox}, {@code password}, {@code number}, {@code checkbox}, {@code select} or {@code tag}
     * @param advanced shown only with advanced settings
     * @param privacy {@code normal}, {@code userName}, {@code password} or {@code apiKey}
     * @param defaultValue its value in a new one
     */
    public record Field(String name, String label, String type, boolean advanced, String privacy,
                        Object defaultValue) {

        /**
         * Whether its value is secret.
         *
         * @return for passwords and API keys
         */
        public boolean secret() {
            return "password".equals(privacy) || "apiKey".equals(privacy);
        }
    }

    /**
     * One implementation: its fields and how Sonarr names it.
     *
     * @param implementation e.g. {@code Torznab}
     * @param implementationName e.g. {@code Torznab}
     * @param configContract e.g. {@code TorznabSettings}
     * @param infoLink Servarr's wiki page
     * @param fields the fields, in order
     */
    public record Implementation(String implementation, String implementationName, String configContract,
                                 String infoLink, List<Field> fields) {

        /**
         * The default settings.
         *
         * @return each field's default
         */
        public Map<String, Object> defaults() {
            Map<String, Object> d = new LinkedHashMap<>();
            fields.forEach(f -> d.put(f.name(), f.defaultValue()));
            return d;
        }
    }

    /** Prowlarr's indexers, as Sonarr's Torznab. */
    public static final Implementation TORZNAB = new Implementation("Torznab", "Torznab", "TorznabSettings",
            "https://wiki.servarr.com/sonarr/supported#torznab", List.of(
                    field("baseUrl", "URL", "textbox", false, "normal", ""),
                    field("apiPath", "API Path", "textbox", true, "normal", "/api"),
                    field("apiKey", "API Key", "textbox", false, "apiKey", ""),
                    field("categories", "Categories", "select", false, "normal", List.of(5030, 5040)),
                    field("animeCategories", "Anime Categories", "select", false, "normal", List.of()),
                    field("animeStandardFormatSearch", "Anime Standard Format Search", "checkbox", false, "normal",
                            false),
                    field("additionalParameters", "Additional Parameters", "textbox", true, "normal", ""),
                    field("multiLanguages", "Multi Languages", "select", true, "normal", List.of()),
                    field("failDownloads", "Fail Downloads", "select", true, "normal", List.of()),
                    field("minimumSeeders", "Minimum Seeders", "number", true, "normal", 1),
                    field("seedCriteria.seedRatio", "Seed Ratio", "textbox", true, "normal", null),
                    field("seedCriteria.seedTime", "Seed Time", "number", true, "normal", null),
                    field("seedCriteria.seasonPackSeedTime", "Season-Pack Seed Time", "number", true, "normal",
                            null),
                    field("rejectBlocklistedTorrentHashesWhileGrabbing", "Reject Blocklisted Torrent Hashes While "
                            + "Grabbing", "checkbox", true, "normal", false)));

    /** qBittorrent, as Sonarr's; the category is Catasonarr's own. */
    public static final Implementation QBITTORRENT = new Implementation("QBittorrent", "qBittorrent",
            "QBittorrentSettings", "https://wiki.servarr.com/sonarr/supported#qbittorrent", List.of(
                    field("host", "Host", "textbox", false, "normal", "localhost"),
                    field("port", "Port", "textbox", false, "normal", 8080),
                    field("useSsl", "Use SSL", "checkbox", false, "normal", false),
                    field("urlBase", "URL Base", "textbox", true, "normal", ""),
                    field("apiKey", "API Key", "textbox", false, "apiKey", ""),
                    field("username", "Username", "textbox", false, "userName", ""),
                    field("password", "Password", "password", false, "password", ""),
                    field("tvCategory", "Category", "textbox", false, "normal", "catasonarr"),
                    field("tvImportedCategory", "Post-Import Category", "textbox", true, "normal", ""),
                    field("recentTvPriority", "Recent Priority", "select", false, "normal", 0),
                    field("olderTvPriority", "Older Priority", "select", false, "normal", 0),
                    field("initialState", "Initial State", "select", false, "normal", 0),
                    field("sequentialOrder", "Sequential Order", "checkbox", false, "normal", false),
                    field("firstAndLast", "First and Last First", "checkbox", false, "normal", false),
                    field("contentLayout", "Content Layout", "select", false, "normal", 0)));

    /** Transmission, as Sonarr's; the category is Catasonarr's own. */
    public static final Implementation TRANSMISSION = new Implementation("Transmission", "Transmission",
            "TransmissionSettings", "https://wiki.servarr.com/sonarr/supported#transmission", List.of(
                    field("host", "Host", "textbox", false, "normal", "localhost"),
                    field("port", "Port", "textbox", false, "normal", 9091),
                    field("useSsl", "Use SSL", "checkbox", false, "normal", false),
                    field("urlBase", "URL Base", "textbox", true, "normal", "/transmission/"),
                    field("username", "Username", "textbox", false, "userName", ""),
                    field("password", "Password", "password", false, "password", ""),
                    field("tvCategory", "Category", "textbox", false, "normal", "catasonarr"),
                    field("tvImportedCategory", "Post-Import Category", "textbox", true, "normal", ""),
                    field("tvDirectory", "Directory", "textbox", true, "normal", ""),
                    field("recentTvPriority", "Recent Priority", "select", false, "normal", 0),
                    field("olderTvPriority", "Older Priority", "select", false, "normal", 0),
                    field("addPaused", "Add Paused", "checkbox", false, "normal", false)));

    private ProviderSchema() {
    }

    private static Field field(String name, String label, String type, boolean advanced, String privacy,
                               Object defaultValue) {
        return new Field(name, label, type, advanced, privacy, defaultValue);
    }

    /**
     * The fields as Sonarr's API sends them, with the values of some settings; secrets masked.
     *
     * @param implementation the implementation
     * @param settings the values, or the defaults for a schema
     * @return Sonarr's {@code fields}
     */
    public static List<Map<String, Object>> fields(Implementation implementation, Map<String, Object> settings) {
        List<Map<String, Object>> out = new ArrayList<>();
        int order = 0;
        for (Field f : implementation.fields()) {
            Object value = settings.containsKey(f.name()) ? settings.get(f.name()) : f.defaultValue();
            if (f.secret() && value != null && !value.toString().isEmpty()) {
                value = PRIVATE_VALUE;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order", order++);
            m.put("name", f.name());
            m.put("label", f.label());
            m.put("value", value);
            m.put("type", f.type());
            m.put("advanced", f.advanced());
            m.put("privacy", f.privacy());
            m.put("isFloat", f.name().equals("seedCriteria.seedRatio"));
            out.add(m);
        }
        return out;
    }

    /**
     * The settings sent in Sonarr's {@code fields}: unknown names are left out, a masked secret keeps the old
     * value, a missing field takes the old value or the default.
     *
     * @param implementation the implementation
     * @param fields what was sent
     * @param old the settings before, or {@code null}
     * @return the settings to keep
     */
    public static Map<String, Object> read(Implementation implementation, List<Map<String, Object>> fields,
                                           Map<String, Object> old) {
        Map<String, Object> sent = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach(f -> sent.put(String.valueOf(f.get("name")), f.get("value")));
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field f : implementation.fields()) {
            Object value;
            if (sent.containsKey(f.name())) {
                value = sent.get(f.name());
                if (f.secret() && PRIVATE_VALUE.equals(value)) {
                    value = old == null ? "" : old.getOrDefault(f.name(), "");
                }
            } else {
                value = old != null && old.containsKey(f.name()) ? old.get(f.name()) : f.defaultValue();
            }
            settings.put(f.name(), value);
        }
        return settings;
    }

    /**
     * An implementation by Sonarr's name.
     *
     * @param name e.g. {@code QBittorrent}
     * @return the implementation, or {@code null}
     */
    public static Implementation byName(String name) {
        for (Implementation i : List.of(TORZNAB, QBITTORRENT, TRANSMISSION)) {
            if (i.implementation().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return null;
    }
}
