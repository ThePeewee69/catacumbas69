package com.catacumbas69.catasonarr.quality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sonarr's qualities and their default definitions, translated from Sonarr 4.0.20's {@code Quality.cs} (GPL-3.0,
 * orch.md decision 109). The ids are Sonarr's, so profiles, definitions and the clients agree with Sonarr.
 */
public final class Qualities {

    /**
     * A quality with its default definition.
     *
     * @param quality the quality
     * @param weight order from worst to best; qualities of a group share it
     * @param minSize default minimum, MB per minute
     * @param maxSize default maximum, MB per minute, or {@code null} for no limit
     * @param preferredSize default preferred size, MB per minute
     * @param groupName the group of the default profiles, e.g. {@code WEB 1080p}, or {@code null}
     */
    public record Default(Quality quality, int weight, double minSize, Double maxSize, double preferredSize,
                          String groupName) {
    }

    /** Every quality with its default definition, in Sonarr's order (by weight). */
    public static final List<Default> DEFAULTS = List.of(
            def(0, "Unknown", "unknown", 0, 1, 1, 199.9, null),
            def(1, "SDTV", "television", 480, 2, 2, 100.0, null),
            def(12, "WEBRip-480p", "webRip", 480, 3, 2, 100.0, "WEB 480p"),
            def(8, "WEBDL-480p", "web", 480, 3, 2, 100.0, "WEB 480p"),
            def(2, "DVD", "dvd", 480, 4, 2, 100.0, null),
            def(13, "Bluray-480p", "bluray", 480, 5, 2, 100.0, null),
            def(22, "Bluray-576p", "bluray", 576, 6, 2, 100.0, null),
            def(4, "HDTV-720p", "television", 720, 7, 3, 125.0, null),
            def(9, "HDTV-1080p", "television", 1080, 8, 4, 125.0, null),
            def(10, "Raw-HD", "televisionRaw", 1080, 9, 4, null, null),
            def(14, "WEBRip-720p", "webRip", 720, 10, 3, 130.0, "WEB 720p"),
            def(5, "WEBDL-720p", "web", 720, 10, 3, 130.0, "WEB 720p"),
            def(6, "Bluray-720p", "bluray", 720, 11, 4, 130.0, null),
            def(15, "WEBRip-1080p", "webRip", 1080, 12, 4, 130.0, "WEB 1080p"),
            def(3, "WEBDL-1080p", "web", 1080, 12, 4, 130.0, "WEB 1080p"),
            def(7, "Bluray-1080p", "bluray", 1080, 13, 4, 155.0, null),
            def(20, "Bluray-1080p Remux", "blurayRaw", 1080, 14, 35, null, null),
            def(16, "HDTV-2160p", "television", 2160, 15, 35, 199.9, null),
            def(17, "WEBRip-2160p", "webRip", 2160, 16, 35, null, "WEB 2160p"),
            def(18, "WEBDL-2160p", "web", 2160, 16, 35, null, "WEB 2160p"),
            def(19, "Bluray-2160p", "bluray", 2160, 17, 35, null, null),
            def(21, "Bluray-2160p Remux", "blurayRaw", 2160, 18, 35, null, null));

    private static final Map<Integer, Default> BY_ID = index();

    private Qualities() {
    }

    /**
     * Finds a quality by Sonarr's id.
     *
     * @param id the id
     * @return the quality with its default definition, or empty for an unknown id
     */
    public static Optional<Default> byId(int id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /**
     * Returns a quality by Sonarr's id.
     *
     * @param id the id
     * @return the quality
     * @throws IllegalArgumentException for an id Sonarr does not have
     */
    public static Quality quality(int id) {
        return byId(id).map(Default::quality)
                .orElseThrow(() -> new IllegalArgumentException("ID does not match a known quality: " + id));
    }

    private static Default def(int id, String name, String source, int resolution, int weight, double min,
                               Double max, String group) {
        return new Default(new Quality(id, name, source, resolution), weight, min, max, 95, group);
    }

    private static Map<Integer, Default> index() {
        Map<Integer, Default> map = new LinkedHashMap<>();
        DEFAULTS.forEach(d -> map.put(d.quality().id(), d));
        return Map.copyOf(map);
    }
}
