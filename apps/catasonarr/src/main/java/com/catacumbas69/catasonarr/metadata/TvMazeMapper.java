package com.catacumbas69.catasonarr.metadata;

import com.catacumbas69.catasonarr.model.AlternateTitle;
import com.catacumbas69.catasonarr.model.Languages;
import com.catacumbas69.catasonarr.model.MediaCover;
import com.catacumbas69.catasonarr.model.Ratings;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns TVmaze's JSON into {@link SeriesInfo} and {@link EpisodeInfo} (orch.md decision 112). TVmaze numbers the
 * regular episodes by season in the order they aired; its specials have no number and sit in the season they aired
 * in, so here they go to season 0, numbered by air date, as TheTVDB and Sonarr keep them.
 */
public final class TvMazeMapper {

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private TvMazeMapper() {
    }

    /**
     * Maps a show, without its episodes.
     *
     * @param show TVmaze's show object
     * @return the series
     */
    public static SeriesInfo show(JsonNode show) {
        JsonNode externals = show.path("externals");
        Instant firstAired = date(text(show, "premiered"));
        String status = status(text(show, "status"));
        int runtime = show.path("runtime").asInt(0);
        if (runtime == 0) {
            runtime = show.path("averageRuntime").asInt(0);
        }
        String network = text(show.path("network"), "name");
        if (network == null) {
            network = text(show.path("webChannel"), "name");
        }
        List<String> genres = new ArrayList<>();
        show.path("genres").forEach(g -> genres.add(g.asText()));
        List<MediaCover> images = new ArrayList<>();
        String poster = text(show.path("image"), "original");
        if (poster != null) {
            images.add(new MediaCover("poster", poster));
        }
        return new SeriesInfo(
                externals.path("thetvdb").asInt(0),
                show.path("id").asInt(0),
                0,
                externals.path("tvrage").asInt(0),
                text(externals, "imdb"),
                text(show, "name"),
                status,
                plain(text(show, "summary")),
                network,
                text(show.path("schedule"), "time"),
                runtime,
                firstAired == null ? 0 : firstAired.atZone(ZoneOffset.UTC).getYear(),
                firstAired,
                "ended".equals(status) ? date(text(show, "ended")) : null,
                genres,
                new Ratings(0, show.path("rating").path("average").asDouble(0)),
                Languages.byName(text(show, "language")),
                images,
                List.of(),
                List.of());
    }

    /**
     * Maps a show's episodes, with the specials.
     *
     * @param episodes TVmaze's array from {@code /shows/{id}/episodes?specials=1}
     * @return the episodes: the regular ones by season and number, then the specials in season 0
     */
    public static List<EpisodeInfo> episodes(JsonNode episodes) {
        List<JsonNode> regular = new ArrayList<>();
        List<JsonNode> specials = new ArrayList<>();
        for (JsonNode e : episodes) {
            if (e.path("number").isNull() || e.path("number").isMissingNode()
                    || text(e, "type") != null && text(e, "type").contains("special")) {
                specials.add(e);
            } else {
                regular.add(e);
            }
        }
        regular.sort(Comparator.comparingInt((JsonNode e) -> e.path("season").asInt())
                .thenComparingInt(e -> e.path("number").asInt()));
        specials.sort(Comparator.comparing((JsonNode e) -> airInstant(e),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(e -> e.path("id").asInt()));
        List<EpisodeInfo> result = new ArrayList<>();
        int absolute = 0;
        for (JsonNode e : regular) {
            absolute++;
            result.add(episode(e, e.path("season").asInt(), e.path("number").asInt(), absolute));
        }
        int special = 0;
        for (JsonNode e : specials) {
            special++;
            result.add(episode(e, 0, special, null));
        }
        return result;
    }

    /**
     * Maps a show's other titles.
     *
     * @param akas TVmaze's array from {@code /shows/{id}/akas}
     * @return the titles, without repeats
     */
    public static List<AlternateTitle> akas(JsonNode akas) {
        List<AlternateTitle> titles = new ArrayList<>();
        for (JsonNode aka : akas) {
            String name = text(aka, "name");
            if (name != null && titles.stream().noneMatch(t -> t.title().equalsIgnoreCase(name))) {
                titles.add(new AlternateTitle(name, null));
            }
        }
        return titles;
    }

    /**
     * Maps TVmaze's status to Sonarr's.
     *
     * @param status {@code Running}, {@code Ended}, {@code To Be Determined} or {@code In Development}
     * @return {@code continuing}, {@code ended} or {@code upcoming}
     */
    public static String status(String status) {
        if (status == null) {
            return "continuing";
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "ended" -> "ended";
            case "in development" -> "upcoming";
            default -> "continuing";
        };
    }

    /**
     * Turns TVmaze's HTML summaries into plain text.
     *
     * @param html e.g. {@code <p>A <b>show</b>.</p>}; may be {@code null}
     * @return the text, or {@code null} when there is none
     */
    public static String plain(String html) {
        if (html == null) {
            return null;
        }
        String text = TAGS.matcher(html).replaceAll(" ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        text = SPACES.matcher(text).replaceAll(" ").strip();
        return text.isEmpty() ? null : text;
    }

    private static EpisodeInfo episode(JsonNode e, int season, int number, Integer absolute) {
        List<MediaCover> images = new ArrayList<>();
        String still = text(e.path("image"), "original");
        if (still != null) {
            images.add(new MediaCover("screenshot", still));
        }
        return new EpisodeInfo(e.path("id").asInt(), season, number, absolute, text(e, "name"),
                plain(text(e, "summary")), text(e, "airdate"), airInstant(e), e.path("runtime").asInt(0), images);
    }

    private static Instant airInstant(JsonNode e) {
        String stamp = text(e, "airstamp");
        if (stamp != null) {
            try {
                return OffsetDateTime.parse(stamp).toInstant();
            } catch (DateTimeParseException ignored) {
                // falls back to the air date
            }
        }
        return date(text(e, "airdate"));
    }

    private static Instant date(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
