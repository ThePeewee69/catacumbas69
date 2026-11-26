package com.catacumbas69.catasonarr.providers;

import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.service.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Indexers and download clients as Sonarr's {@code IndexerResource} and {@code DownloadClientResource}
 * (orch.md decision 122), and back.
 */
public final class ProviderResources {

    private ProviderResources() {
    }

    /**
     * An indexer as Sonarr's API shows it.
     *
     * @param i the indexer
     * @return its resource
     */
    public static Map<String, Object> indexer(Indexer i) {
        Map<String, Object> r = base(i.id(), i.name(), ProviderSchema.TORZNAB, i.settings(), i.tags());
        r.put("enableRss", i.enableRss());
        r.put("enableAutomaticSearch", i.enableAutomaticSearch());
        r.put("enableInteractiveSearch", i.enableInteractiveSearch());
        r.put("supportsRss", true);
        r.put("supportsSearch", true);
        r.put("protocol", "torrent");
        r.put("priority", i.priority());
        r.put("seasonSearchMaximumSingleEpisodeAge", i.seasonSearchMaximumSingleEpisodeAge());
        r.put("downloadClientId", i.downloadClientId());
        return r;
    }

    /**
     * The schema of a new indexer, as {@code /api/v3/indexer/schema}: Torznab only.
     *
     * @return the schemas
     */
    public static List<Map<String, Object>> indexerSchema() {
        return List.of(indexer(new Indexer(0, "", "Torznab", ProviderSchema.TORZNAB.defaults(), true, true, true, 25,
                0, 0, Set.of())));
    }

    /**
     * An indexer from what Sonarr's API sends; Newznab (usenet) is refused, as Catasonarr is torrents only.
     *
     * @param id the id it gets
     * @param body what was sent
     * @param old the indexer before, or {@code null}
     * @return the indexer
     */
    public static Indexer indexer(int id, Map<String, Object> body, Indexer old) {
        String implementation = text(body.get("implementation"), "Torznab");
        if (!"Torznab".equalsIgnoreCase(implementation)) {
            throw new BadRequestException("implementation",
                    "Catasonarr only takes Torznab indexers (torrents), not " + implementation);
        }
        Map<String, Object> settings = ProviderSchema.read(ProviderSchema.TORZNAB, fields(body),
                old == null ? null : old.settings());
        if (text(settings.get("baseUrl"), "").isBlank()) {
            throw new BadRequestException("baseUrl", "'URL' must not be empty.");
        }
        String name = text(body.get("name"), "");
        if (name.isBlank()) {
            throw new BadRequestException("name", "'Name' must not be empty.");
        }
        int priority = number(body.get("priority"), 25);
        if (priority < 1 || priority > 50) {
            throw new BadRequestException("priority", "'Priority' must be between 1 and 50");
        }
        return new Indexer(id, name.strip(), "Torznab", settings, bool(body.get("enableRss"), true),
                bool(body.get("enableAutomaticSearch"), true), bool(body.get("enableInteractiveSearch"), true),
                priority, number(body.get("seasonSearchMaximumSingleEpisodeAge"), 0),
                number(body.get("downloadClientId"), 0), tags(body.get("tags")));
    }

    /**
     * A download client as Sonarr's API shows it.
     *
     * @param c the client
     * @return its resource
     */
    public static Map<String, Object> client(DownloadClient c) {
        ProviderSchema.Implementation impl = ProviderSchema.byName(c.implementation());
        Map<String, Object> r = base(c.id(), c.name(), impl, c.settings(), c.tags());
        r.put("enable", c.enable());
        r.put("protocol", "torrent");
        r.put("priority", c.priority());
        r.put("removeCompletedDownloads", c.removeCompletedDownloads());
        r.put("removeFailedDownloads", c.removeFailedDownloads());
        return r;
    }

    /**
     * The schemas of a new client: qBittorrent and Transmission.
     *
     * @return the schemas
     */
    public static List<Map<String, Object>> clientSchema() {
        return List.of(ProviderSchema.QBITTORRENT, ProviderSchema.TRANSMISSION).stream()
                .map(i -> client(new DownloadClient(0, "", i.implementation(), i.defaults(), true, 1, true, true,
                        Set.of()))).toList();
    }

    /**
     * A download client from what Sonarr's API sends.
     *
     * @param id the id it gets
     * @param body what was sent
     * @param old the client before, or {@code null}
     * @return the client
     */
    public static DownloadClient client(int id, Map<String, Object> body, DownloadClient old) {
        String implementation = text(body.get("implementation"), "");
        ProviderSchema.Implementation impl = ProviderSchema.byName(implementation);
        if (impl == null || impl == ProviderSchema.TORZNAB) {
            throw new BadRequestException("implementation",
                    "Catasonarr takes qBittorrent and Transmission, not " + implementation);
        }
        Map<String, Object> settings = ProviderSchema.read(impl, fields(body), old == null ? null : old.settings());
        if (text(settings.get("host"), "").isBlank()) {
            throw new BadRequestException("host", "'Host' must not be empty.");
        }
        if (text(settings.get("tvCategory"), "").isBlank()) {
            throw new BadRequestException("tvCategory", "Catasonarr needs its own category (catasonarr), so it only "
                    + "touches its own downloads");
        }
        String name = text(body.get("name"), "");
        if (name.isBlank()) {
            throw new BadRequestException("name", "'Name' must not be empty.");
        }
        int priority = number(body.get("priority"), 1);
        if (priority < 1 || priority > 50) {
            throw new BadRequestException("priority", "'Priority' must be between 1 and 50");
        }
        return new DownloadClient(id, name.strip(), impl.implementation(), settings, bool(body.get("enable"), true),
                priority, bool(body.get("removeCompletedDownloads"), true),
                bool(body.get("removeFailedDownloads"), true), tags(body.get("tags")));
    }

    private static Map<String, Object> base(int id, String name, ProviderSchema.Implementation impl,
                                            Map<String, Object> settings, Set<Integer> tags) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("name", name);
        r.put("fields", ProviderSchema.fields(impl, settings));
        r.put("implementationName", impl.implementationName());
        r.put("implementation", impl.implementation());
        r.put("configContract", impl.configContract());
        r.put("infoLink", impl.infoLink());
        r.put("tags", tags == null ? List.of() : new TreeSet<>(tags));
        r.put("presets", List.of());
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> body) {
        Object f = body.get("fields");
        return f instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static Set<Integer> tags(Object value) {
        Set<Integer> tags = new TreeSet<>();
        if (value instanceof List<?> l) {
            l.stream().filter(Number.class::isInstance).forEach(n -> tags.add(((Number) n).intValue()));
        }
        return tags;
    }

    private static String text(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    private static int number(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? fallback : Integer.parseInt(o.toString().strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : o == null ? fallback : Boolean.parseBoolean(o.toString());
    }
}
