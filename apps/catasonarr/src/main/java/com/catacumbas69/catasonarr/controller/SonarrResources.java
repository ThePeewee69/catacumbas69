package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.AlternateTitleResource;
import com.catacumbas69.catasonarr.dto.CommandResource;
import com.catacumbas69.catasonarr.dto.CustomFormatResource;
import com.catacumbas69.catasonarr.dto.EpisodeResource;
import com.catacumbas69.catasonarr.dto.MediaCoverResource;
import com.catacumbas69.catasonarr.dto.QualityDefinitionResource;
import com.catacumbas69.catasonarr.dto.QualityProfileResource;
import com.catacumbas69.catasonarr.dto.RootFolderResource;
import com.catacumbas69.catasonarr.dto.SeasonResource;
import com.catacumbas69.catasonarr.dto.SeasonStatisticsResource;
import com.catacumbas69.catasonarr.dto.SeriesResource;
import com.catacumbas69.catasonarr.dto.SeriesStatisticsResource;
import com.catacumbas69.catasonarr.dto.TagResource;
import com.catacumbas69.catasonarr.metadata.EpisodeInfo;
import com.catacumbas69.catasonarr.metadata.SeriesInfo;
import com.catacumbas69.catasonarr.model.AlternateTitle;
import com.catacumbas69.catasonarr.model.Command;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.FormatItem;
import com.catacumbas69.catasonarr.model.MediaCover;
import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.Season;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Specification;
import com.catacumbas69.catasonarr.model.Tag;
import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import com.catacumbas69.catasonarr.service.QualityService;
import com.catacumbas69.catasonarr.service.RootFolderService;
import com.catacumbas69.catasonarr.service.Statistics;
import com.catacumbas69.catasonarr.service.Titles;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns Catasonarr's model into Sonarr's API v3 resources and back (orch.md decision 112).
 */
public final class SonarrResources {

    private static final String WIKI = "https://wiki.servarr.com/sonarr/settings#custom-formats-2";
    private static final Map<String, String> IMPLEMENTATION_NAMES = Map.of(
            "ReleaseTitleSpecification", "Release Title",
            "ReleaseGroupSpecification", "Release Group",
            "LanguageSpecification", "Language",
            "SourceSpecification", "Source",
            "ResolutionSpecification", "Resolution",
            "SizeSpecification", "Size",
            "IndexerFlagSpecification", "Indexer Flag",
            "ReleaseTypeSpecification", "Release Type");

    private SonarrResources() {
    }

    /**
     * A series in the library.
     *
     * @param s the series
     * @param seasonStatistics its seasons' statistics, by season number
     * @return the resource
     */
    public static SeriesResource series(Series s, Map<Integer, Statistics> seasonStatistics) {
        Map<Integer, Statistics> stats = seasonStatistics == null ? Map.of() : seasonStatistics;
        List<SeasonResource> seasons = s.seasons().stream().map(season -> new SeasonResource(season.seasonNumber(),
                season.monitored(), seasonStatistics(stats.getOrDefault(season.seasonNumber(),
                Statistics.empty(season.seasonNumber()))), null)).toList();
        Statistics total = Statistics.sum(stats.values());
        int seasonCount = (int) s.seasons().stream().filter(season -> season.seasonNumber() > 0).count();
        Path path = s.path() == null ? null : Path.of(s.path());
        return new SeriesResource(s.id(), s.title(), alternateTitles(s.alternateTitles()), s.sortTitle(), s.status(),
                "ended".equals(s.status()), null, s.overview(), total.nextAiring(), total.previousAiring(),
                s.network(), s.airTime(), images(s.images()), s.originalLanguage(), poster(s.images()), seasons,
                s.year(), s.path(), s.qualityProfileId(), s.seasonFolder(), s.monitored(), s.monitorNewItems(),
                s.useSceneNumbering(), s.runtime(), s.tvdbId(), s.tvRageId(), s.tvMazeId(), s.tmdbId(),
                s.firstAired(), s.lastAired(), s.seriesType(), s.cleanTitle(), s.imdbId(), s.titleSlug(),
                path == null || path.getParent() == null ? null : path.getParent().toString(),
                path == null || path.getFileName() == null ? null : path.getFileName().toString(), s.certification(),
                s.genres(), s.tags(), s.added(), null, s.ratings(),
                new SeriesStatisticsResource(seasonCount, total.episodeFileCount(), total.episodeCount(),
                        total.totalEpisodeCount(), total.sizeOnDisk(), List.of(), total.percentOfEpisodes()),
                null, 1);
    }

    /**
     * A series found by {@code series/lookup} that is not in the library.
     *
     * @param info the series
     * @param folder the folder it would get
     * @return the resource, with id 0
     */
    public static SeriesResource lookup(SeriesInfo info, String folder) {
        TreeSet<Integer> numbers = new TreeSet<>();
        info.episodes().stream().map(EpisodeInfo::seasonNumber).forEach(numbers::add);
        List<SeasonResource> seasons = numbers.stream()
                .map(n -> new SeasonResource(n, n > 0, null, null)).toList();
        return new SeriesResource(0, info.title(), alternateTitles(info.alternateTitles()),
                Titles.sort(info.title()), info.status(), "ended".equals(info.status()), null, info.overview(), null,
                null, info.network(), info.airTime(), images(info.images()), info.originalLanguage(),
                poster(info.images()), seasons, info.year(), null, 0, true, false, "all", false, info.runtime(),
                info.tvdbId(), info.tvRageId(), info.tvMazeId(), info.tmdbId(), info.firstAired(), info.lastAired(),
                "standard", Titles.clean(info.title()), info.imdbId(), Titles.slug(info.title()), null, folder, null,
                info.genres(), Set.of(), null, null, info.ratings(),
                null, null, 1);
    }

    /**
     * An episode.
     *
     * @param e the episode
     * @param series its series, when asked for, or {@code null}
     * @return the resource
     */
    public static EpisodeResource episode(Episode e, SeriesResource series) {
        return episode(e, series, 0);
    }

    /**
     * An episode with its file.
     *
     * @param e the episode
     * @param series its series, when asked for, or {@code null}
     * @param episodeFileId its file's id, or 0
     * @return the resource
     */
    public static EpisodeResource episode(Episode e, SeriesResource series, int episodeFileId) {
        Instant end = e.airDateUtc() == null ? null : e.airDateUtc().plusSeconds(e.runtime() * 60L);
        return new EpisodeResource(e.id(), e.seriesId(), 0, episodeFileId, e.seasonNumber(), e.episodeNumber(),
                e.title(), e.airDate(), e.airDateUtc(), null, e.runtime(), null, e.overview(), episodeFileId > 0,
                e.monitored(),
                e.absoluteEpisodeNumber(), null, null, null, false, end, null, series, images(e.images()));
    }

    /**
     * A root folder.
     *
     * @param d the folder, described
     * @return the resource
     */
    public static RootFolderResource rootFolder(RootFolderService.Described d) {
        Path root = Path.of(d.folder().path());
        List<RootFolderResource.UnmappedFolder> unmapped = d.unmappedFolders().stream()
                .map(p -> new RootFolderResource.UnmappedFolder(p.getFileName().toString(), p.toString(),
                        root.relativize(p).toString()))
                .toList();
        return new RootFolderResource(d.folder().id(), d.folder().path(), d.accessible(), d.freeSpace(), unmapped);
    }

    /**
     * A quality profile, with every custom format's score.
     *
     * @param p the profile
     * @param formatNames every custom format's name, by id
     * @return the resource
     */
    public static QualityProfileResource qualityProfile(QualityProfile p, Map<Integer, String> formatNames) {
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        if (p.formatItems() != null) {
            p.formatItems().forEach(f -> scores.put(f.format(), f.score()));
        }
        List<QualityProfileResource.FormatItem> formats = formatNames.entrySet().stream()
                .map(f -> new QualityProfileResource.FormatItem(f.getKey(), f.getValue(),
                        scores.getOrDefault(f.getKey(), 0)))
                .toList();
        return new QualityProfileResource(p.id(), p.name(), p.upgradeAllowed(), p.cutoff(), items(p.items()),
                p.minFormatScore(), p.cutoffFormatScore(), p.minUpgradeFormatScore(), formats);
    }

    /**
     * A quality profile from the API.
     *
     * @param r the resource
     * @return the profile
     */
    public static QualityProfile qualityProfile(QualityProfileResource r) {
        List<FormatItem> formats = r.formatItems() == null ? List.of()
                : r.formatItems().stream().map(f -> new FormatItem(f.format(), f.score())).toList();
        return new QualityProfile(r.id(), r.name(), r.upgradeAllowed(), r.cutoff(), profileItems(r.items()),
                r.minFormatScore(), r.cutoffFormatScore(), r.minUpgradeFormatScore(), formats);
    }

    /**
     * A quality definition.
     *
     * @param d the definition
     * @return the resource
     */
    public static QualityDefinitionResource qualityDefinition(QualityDefinition d) {
        Quality quality = Qualities.byId(d.quality()).map(Qualities.Default::quality)
                .orElse(new Quality(d.quality(), d.title(), "unknown", 0));
        return new QualityDefinitionResource(d.id(), quality, d.title(),
                QualityService.weight(d.quality()), d.minSize(), d.maxSize(),
                d.preferredSize());
    }

    /**
     * A custom format.
     *
     * @param f the format
     * @return the resource
     */
    public static CustomFormatResource customFormat(CustomFormat f) {
        List<CustomFormatResource.Specification> specs = new ArrayList<>();
        if (f.specifications() != null) {
            for (Specification s : f.specifications()) {
                specs.add(new CustomFormatResource.Specification(0, s.name(), s.implementation(),
                        IMPLEMENTATION_NAMES.getOrDefault(s.implementation(), s.implementation()), WIKI, s.negate(),
                        s.required(), fields(s)));
            }
        }
        return new CustomFormatResource(f.id(), f.name(), f.includeCustomFormatWhenRenaming(), specs);
    }

    /**
     * A custom format from the API.
     *
     * @param r the resource
     * @return the format
     */
    public static CustomFormat customFormat(CustomFormatResource r) {
        List<Specification> specs = new ArrayList<>();
        if (r.specifications() != null) {
            for (CustomFormatResource.Specification s : r.specifications()) {
                Map<String, Object> values = new LinkedHashMap<>();
                if (s.fields() != null) {
                    s.fields().forEach(f -> values.put(f.name(), f.value()));
                }
                specs.add(new Specification(s.implementation(), s.name(), s.negate(), s.required(), values));
            }
        }
        return new CustomFormat(r.id(), r.name(), r.includeCustomFormatWhenRenaming(), specs);
    }

    /**
     * Sonarr's {@code customformat/schema}: one empty condition of each kind.
     *
     * @return the templates
     */
    public static List<CustomFormatResource.Specification> customFormatSchema() {
        List<CustomFormatResource.Specification> schema = new ArrayList<>();
        IMPLEMENTATION_NAMES.forEach((implementation, name) -> schema.add(new CustomFormatResource.Specification(0,
                null, implementation, name, WIKI, false, false,
                fields(new Specification(implementation, null, false, false, Map.of())))));
        return schema;
    }

    /**
     * A tag.
     *
     * @param t the tag
     * @return the resource
     */
    public static TagResource tag(Tag t) {
        return new TagResource(t.id(), t.label());
    }

    /**
     * A command.
     *
     * @param c the command
     * @return the resource
     */
    public static CommandResource command(Command c) {
        Instant changed = c.ended() != null ? c.ended() : c.started() != null ? c.started() : c.queued();
        return new CommandResource(c.id(), c.name(), c.name().replaceAll("(?<=[a-z])(?=[A-Z])", " "), c.message(),
                c.body(), "normal", c.status(), c.result(), c.queued(), c.started(), c.ended(), c.trigger(), changed,
                false, false);
    }

    /**
     * The seasons asked in a series resource.
     *
     * @param seasons the resource's seasons, or {@code null}
     * @return the seasons
     */
    public static List<Season> seasons(List<SeasonResource> seasons) {
        return seasons == null ? List.of()
                : seasons.stream().map(s -> new Season(s.seasonNumber(), s.monitored())).toList();
    }

    private static List<CustomFormatResource.Field> fields(Specification s) {
        Map<String, Object> v = s.values() == null ? Map.of() : s.values();
        String impl = s.implementation() == null ? "" : s.implementation();
        return switch (impl) {
            case "SizeSpecification" -> List.of(
                    new CustomFormatResource.Field(0, "min", "Minimum Size", "GB", v.getOrDefault("min", 0),
                            "number", false, "normal", true),
                    new CustomFormatResource.Field(1, "max", "Maximum Size", "GB", v.getOrDefault("max", 0),
                            "number", false, "normal", true));
            case "LanguageSpecification" -> List.of(
                    new CustomFormatResource.Field(0, "value", "Language", null, v.getOrDefault("value", 0),
                            "select", false, "normal", false),
                    new CustomFormatResource.Field(1, "exceptLanguage", "Except Language", null,
                            v.getOrDefault("exceptLanguage", false), "checkbox", false, "normal", false));
            case "ReleaseTitleSpecification", "ReleaseGroupSpecification" -> List.of(
                    new CustomFormatResource.Field(0, "value", "Regular Expression", null, v.getOrDefault("value", ""),
                            "textbox", false, "normal", false));
            default -> List.of(new CustomFormatResource.Field(0, "value", IMPLEMENTATION_NAMES.getOrDefault(impl,
                    "Value"), null, v.getOrDefault("value", 0), "select", false, "normal", false));
        };
    }

    private static List<QualityProfileResource.Item> items(List<ProfileItem> items) {
        List<QualityProfileResource.Item> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (ProfileItem i : items) {
            if (i.quality() != null) {
                Qualities.byId(i.quality()).ifPresent(q -> result.add(
                        new QualityProfileResource.Item(0, null, q.quality(), List.of(), i.allowed())));
            } else {
                result.add(new QualityProfileResource.Item(i.id() == null ? 0 : i.id(), i.name(), null,
                        items(i.items()), i.allowed()));
            }
        }
        return result;
    }

    private static List<ProfileItem> profileItems(List<QualityProfileResource.Item> items) {
        List<ProfileItem> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (QualityProfileResource.Item i : items) {
            if (i.quality() != null) {
                result.add(new ProfileItem(null, null, i.quality().id(), List.of(), i.allowed()));
            } else {
                result.add(new ProfileItem(i.id(), i.name(), null, profileItems(i.items()), i.allowed()));
            }
        }
        return result;
    }

    private static List<MediaCoverResource> images(List<MediaCover> images) {
        return images == null ? List.of() : images.stream().filter(Objects::nonNull)
                .map(i -> new MediaCoverResource(i.coverType(), i.remoteUrl(), i.remoteUrl())).toList();
    }

    private static String poster(List<MediaCover> images) {
        return images == null ? null : images.stream().filter(i -> "poster".equals(i.coverType()))
                .map(MediaCover::remoteUrl).findFirst().orElse(null);
    }

    private static List<AlternateTitleResource> alternateTitles(
            List<AlternateTitle> titles) {
        return titles == null ? List.of() : titles.stream()
                .map(t -> new AlternateTitleResource(t.title(), t.seasonNumber(), null, "tvmaze", null)).toList();
    }

    private static SeasonStatisticsResource seasonStatistics(Statistics s) {
        return new SeasonStatisticsResource(s.nextAiring(), s.previousAiring(), s.episodeFileCount(), s.episodeCount(),
                s.totalEpisodeCount(), s.sizeOnDisk(), List.of(), s.percentOfEpisodes());
    }
}
