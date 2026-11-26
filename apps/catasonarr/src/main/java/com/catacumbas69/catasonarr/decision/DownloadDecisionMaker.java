package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.indexers.IndexerService;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.DelayProfile;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.model.PendingRelease;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.ReleaseProfile;
import com.catacumbas69.catasonarr.model.Settings;
import com.catacumbas69.catasonarr.parser.LanguageParser;
import com.catacumbas69.catasonarr.parser.NetRegex;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import com.catacumbas69.catasonarr.parser.QualityParser;
import com.catacumbas69.catasonarr.service.BlocklistService;
import com.catacumbas69.catasonarr.service.EpisodeFileService;
import com.catacumbas69.catasonarr.service.HistoryService;
import com.catacumbas69.catasonarr.service.PendingReleaseService;
import com.catacumbas69.catasonarr.service.ProfileService;
import com.catacumbas69.catasonarr.service.SettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Decides which releases to grab, translated from Sonarr's {@code DownloadDecisionMaker}, its specifications and its
 * {@code DownloadDecisionComparer} (GPL-3.0, orch.md decision 122). Each release is parsed and matched; its custom
 * formats are scored; then Sonarr's rules run, those that read the disk last, and every reason not to grab it is
 * kept. Approved releases are sorted as Sonarr sorts them: quality, custom format score, episode count and number,
 * indexer priority, seeders and peers, size.
 */
@Service
public class DownloadDecisionMaker {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadDecisionMaker.class);
    private static final long MB = 1024L * 1024L;
    private static final List<NetRegex> DISC = List.of(
            NetRegex.compile("(?:dis[ck])(?:[-_. ]\\d+[-_. ])(?:(?:(?:480|720|1080|2160)[ip]|)[-_. ])?(?:Blu\\-?ray)",
                    NetRegex.IGNORE_CASE),
            NetRegex.compile("(?:(?:480|720|1080|2160)[ip]|)[-_. ](?:full)[-_. ](?:Blu\\-?ray)", NetRegex.IGNORE_CASE),
            NetRegex.compile("(?:\\d?x?M?DVD-?[R59])(?:[ ._]|$)", NetRegex.IGNORE_CASE));

    /** One of Sonarr's rules: the reason not to grab, or {@code null}. */
    private interface Spec {
        Rejection check(RemoteEpisode r, SearchCriteria c, Settings s);
    }

    private final ReleaseMatcher matcher;
    private final Library library;
    private final CustomFormatCalculator formats;
    private final SettingsService settings;
    private final IndexerService indexers;
    private final EpisodeFileService files;
    private final HistoryService history;
    private final BlocklistService blocklist;
    private final PendingReleaseService pending;
    private final ProfileService profiles;
    private final QueueView queue;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final List<Spec> specs;
    private final List<Spec> diskSpecs;

    /**
     * Creates the decision maker.
     *
     * @param matcher matches releases to series and episodes
     * @param library the series and episodes
     * @param formats custom formats
     * @param settings Catasonarr's settings
     * @param indexers the indexers
     * @param files the episodes' files
     * @param history the history
     * @param blocklist the blocklist
     * @param pending held-back releases
     * @param profiles release and delay profiles
     * @param queue what is downloading
     * @param mongo MongoDB, for the quality definitions
     * @param clock the time
     */
    public DownloadDecisionMaker(ReleaseMatcher matcher, Library library, CustomFormatCalculator formats,
                                 SettingsService settings,
                                 IndexerService indexers, EpisodeFileService files, HistoryService history,
                                 BlocklistService blocklist, PendingReleaseService pending, ProfileService profiles,
                                 @Lazy QueueView queue, MongoTemplate mongo, Clock clock) {
        this.matcher = matcher;
        this.library = library;
        this.formats = formats;
        this.settings = settings;
        this.indexers = indexers;
        this.files = files;
        this.history = history;
        this.blocklist = blocklist;
        this.pending = pending;
        this.profiles = profiles;
        this.queue = queue;
        this.mongo = mongo;
        this.clock = clock;
        this.specs = List.of(this::acceptableSize, this::maximumSize, this::minimumAge, this::torrentSeeding,
                this::notSample, this::rawDisk, this::fullSeason, this::multiSeason, this::splitEpisode,
                this::seasonPackOnly, this::qualityAllowed, this::customFormatAllowed, this::upgradeAllowed,
                this::upgradeDisk, this::sameEpisodesGrab, this::repack, this::animeVersionUpgrade,
                this::releaseRestrictions, this::protocol, this::indexerTag, this::blocklisted, this::queueSpec,
                this::alreadyImported, this::historySpec, this::proper, this::monitoredEpisode, this::delay,
                this::seriesSpec, this::seasonMatch, this::episodeRequested, this::singleEpisodeSearchMatch);
        this.diskSpecs = List.of(this::freeSpace);
    }

    /**
     * Decisions for the releases of an RSS sync.
     *
     * @param releases the releases
     * @return a decision for each one that parsed
     */
    public List<DownloadDecision> rss(List<ReleaseInfo> releases) {
        return decisions(releases, null);
    }

    /**
     * Decisions for the releases of a search.
     *
     * @param releases the releases
     * @param criteria what was searched
     * @return a decision for each release
     */
    public List<DownloadDecision> search(List<ReleaseInfo> releases, SearchCriteria criteria) {
        return decisions(releases, criteria);
    }

    /**
     * The decisions sorted best first within each series, as Sonarr's prioritization.
     *
     * @param decisions the decisions
     * @return them sorted
     */
    public List<DownloadDecision> prioritize(List<DownloadDecision> decisions) {
        Settings s = settings.get();
        Comparator<DownloadDecision> best = comparator(s).reversed();
        Map<Integer, List<DownloadDecision>> bySeries = decisions.stream().filter(d -> d.remoteEpisode().series != null)
                .collect(Collectors.groupingBy(d -> d.remoteEpisode().series.id(), java.util.LinkedHashMap::new,
                        Collectors.toList()));
        List<DownloadDecision> out = new ArrayList<>();
        bySeries.values().forEach(list -> list.stream().sorted(best).forEach(out::add));
        decisions.stream().filter(d -> d.remoteEpisode().series == null).forEach(out::add);
        return out;
    }

    private List<DownloadDecision> decisions(List<ReleaseInfo> releases, SearchCriteria criteria) {
        Settings s = settings.get();
        List<DownloadDecision> out = new ArrayList<>();
        for (ReleaseInfo release : releases) {
            DownloadDecision d;
            try {
                d = decide(release, criteria, s);
            } catch (RuntimeException e) {
                LOG.warn("Couldn't process release {}: {}", release.title(), e.toString());
                RemoteEpisode r = new RemoteEpisode();
                r.release = release;
                r.parsed = new ParsedEpisodeInfo();
                r.parsed.quality = QualityParser.parseQuality(release.title());
                d = new DownloadDecision(r, List.of(Rejection.of("error", "Unexpected error processing release")));
            }
            if (d != null) {
                d.remoteEpisode().releaseSource = criteria == null ? "Rss" : criteria.interactiveSearch()
                        ? "InteractiveSearch" : criteria.userInvokedSearch() ? "UserInvokedSearch" : "Search";
                out.add(d);
            }
        }
        return out;
    }

    private DownloadDecision decide(ReleaseInfo release, SearchCriteria criteria, Settings s) {
        ParsedEpisodeInfo parsed = matcher.parse(release, criteria);
        if (parsed != null && parsed.seriesTitle != null && !parsed.seriesTitle.isBlank()) {
            RemoteEpisode r = matcher.map(parsed, release.tvdbId(), release.imdbId(), criteria);
            r.release = release;
            if (r.series == null) {
                return new DownloadDecision(r, List.of(Rejection.of("unknownSeries", "Unknown Series")));
            }
            if (r.episodes.isEmpty()) {
                return new DownloadDecision(r, List.of(Rejection.of("unknownEpisode",
                        "Unable to identify correct episode(s) using release name and scene mappings")));
            }
            r.customFormats = formats.forRelease(r);
            r.customFormatScore = r.profile == null ? 0 : ProfileQualities.score(r.profile, r.customFormats);
            List<Rejection> reasons = evaluate(specs, r, criteria, s);
            if (reasons.isEmpty()) {
                reasons = evaluate(diskSpecs, r, criteria, s);
            }
            return new DownloadDecision(r, reasons);
        }
        if (criteria == null) {
            return null;
        }
        RemoteEpisode r = new RemoteEpisode();
        r.release = release;
        r.parsed = parsed != null ? parsed : new ParsedEpisodeInfo();
        if (parsed == null) {
            r.parsed.languages = LanguageParser.parseLanguages(release.title());
            r.parsed.quality = QualityParser.parseQuality(release.title());
        }
        r.languages = r.parsed.languages;
        return new DownloadDecision(r, List.of(Rejection.of("unableToParse", "Unable to parse release")));
    }

    private List<Rejection> evaluate(List<Spec> list, RemoteEpisode r, SearchCriteria c, Settings s) {
        List<Rejection> reasons = new ArrayList<>();
        for (Spec spec : list) {
            try {
                Rejection rejection = spec.check(r, c, s);
                if (rejection != null) {
                    reasons.add(rejection);
                }
            } catch (RuntimeException e) {
                LOG.warn("Couldn't evaluate decision on {}: {}", r.release.title(), e.toString());
                reasons.add(Rejection.of("decisionError", e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return reasons;
    }

    // ── Sonarr's specifications ─────────────────────────────────────────────

    private Rejection acceptableSize(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (r.parsed.special || r.release.size() == 0) {
            return null;
        }
        int seriesRuntime = r.series.runtime();
        if (seriesRuntime == 0) {
            int first = r.series.seasons().stream().filter(x -> x.seasonNumber() > 0)
                    .mapToInt(com.catacumbas69.catasonarr.model.Season::seasonNumber).min().orElse(1);
            List<Episode> firstSeason = library.season(r.series.id(), first);
            if (!firstSeason.isEmpty() && r.episodes.get(0).seasonNumber() == firstSeason.get(0).seasonNumber()) {
                Instant pilot = firstSeason.get(0).airDateUtc() != null ? firstSeason.get(0).airDateUtc()
                        : r.release.publishDate();
                Instant grace = pilot.plus(24, ChronoUnit.HOURS);
                if (r.episodes.stream().allMatch(e -> e.airDateUtc() == null || e.airDateUtc().isBefore(grace))) {
                    seriesRuntime = 45;
                }
            }
        }
        int runtime = 0;
        for (Episode e : r.episodes) {
            runtime += e.runtime() > 0 ? e.runtime() : seriesRuntime;
        }
        if (runtime == 0) {
            return Rejection.of("unknownRuntime",
                    "Runtime of all episodes is 0, unable to validate size until it is available");
        }
        QualityDefinition def = definition(r.parsed.quality.quality.id());
        String runtimeText = r.episodes.size() == 1 ? runtime + "min" : r.episodes.size() + "x " + runtime + "min";
        if (def != null && def.minSize() != null) {
            long min = (long) (def.minSize() * MB) * runtime;
            if (r.release.size() < min) {
                return Rejection.of("belowMinimumSize", size(r.release.size()) + " is smaller than minimum allowed "
                        + size(min) + " (for " + runtimeText + ")");
            }
        }
        if (def != null && def.maxSize() != null && def.maxSize() != 0) {
            long max = (long) (def.maxSize() * MB) * runtime;
            if (r.release.size() > max) {
                return Rejection.of("aboveMaximumSize", size(r.release.size()) + " is larger than maximum allowed "
                        + size(max) + " (for " + runtimeText + ")");
            }
        }
        return null;
    }

    private Rejection maximumSize(RemoteEpisode r, SearchCriteria c, Settings s) {
        long max = s.maximumSize() * MB;
        if (max == 0 || r.release.size() == 0 || r.release.size() <= max) {
            return null;
        }
        return Rejection.of("maximumSizeExceeded", size(r.release.size()) + " is too big, maximum size is "
                + size(max) + " (Settings → Indexers → Maximum Size)");
    }

    /** Sonarr applies it to usenet only; Catasonarr, torrents only, applies it to torrents (decision 122). */
    private Rejection minimumAge(RemoteEpisode r, SearchCriteria c, Settings s) {
        double age = r.release.ageMinutes(clock.instant());
        if (s.minimumAge() == 0 || age >= s.minimumAge()) {
            return null;
        }
        return new Rejection("minimumAge", String.format("Only %.1f minutes old, minimum age is %d minutes", age,
                s.minimumAge()), true);
    }

    private Rejection torrentSeeding(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (r.release.indexerId() == 0 || r.release.seeders() == null) {
            return null;
        }
        Optional<Indexer> indexer = indexers.find(r.release.indexerId());
        int minimum = indexer.map(i -> i.settings().get("minimumSeeders")).map(DownloadDecisionMaker::number)
                .orElse(0);
        if (r.release.seeders() < minimum) {
            return Rejection.of("minimumSeeders", "Not enough seeders: " + r.release.seeders() + ". Minimum seeders: "
                    + minimum);
        }
        return null;
    }

    private Rejection notSample(RemoteEpisode r, SearchCriteria c, Settings s) {
        return r.release.title().toLowerCase().contains("sample") && r.release.size() < 70 * MB
                ? Rejection.of("sample", "Sample") : null;
    }

    private Rejection rawDisk(RemoteEpisode r, SearchCriteria c, Settings s) {
        return DISC.stream().anyMatch(re -> re.isMatch(r.release.title()))
                ? Rejection.of("raw", "Raw Bluray/DVD release") : null;
    }

    private Rejection fullSeason(RemoteEpisode r, SearchCriteria c, Settings s) {
        Instant limit = clock.instant().plus(24, ChronoUnit.HOURS);
        if (r.parsed.fullSeason && r.episodes.stream()
                .anyMatch(e -> e.airDateUtc() == null || e.airDateUtc().isAfter(limit))) {
            return Rejection.of("fullSeasonNotAired", "Full season release rejected. All episodes haven't aired yet.");
        }
        return null;
    }

    private Rejection multiSeason(RemoteEpisode r, SearchCriteria c, Settings s) {
        return r.parsed.isMultiSeason ? Rejection.of("multiSeason", "Multi-season releases are not supported") : null;
    }

    private Rejection splitEpisode(RemoteEpisode r, SearchCriteria c, Settings s) {
        return r.parsed.isSplitEpisode ? Rejection.of("splitEpisode", "Split episode releases are not supported")
                : null;
    }

    private Rejection seasonPackOnly(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c == null || c.episodes().size() == 1 || r.release.indexerId() == 0) {
            return null;
        }
        int maxAge = indexers.find(r.release.indexerId()).map(Indexer::seasonSearchMaximumSingleEpisodeAge).orElse(0);
        if (maxAge > 0 && "standard".equals(r.series.seriesType()) && !r.parsed.fullSeason && !r.episodes.isEmpty()) {
            int season = r.episodes.get(0).seasonNumber();
            Optional<Instant> last = c.episodes().stream().filter(e -> e.airDateUtc() != null
                    && e.seasonNumber() == season).map(Episode::airDateUtc).max(Comparator.naturalOrder());
            if (last.isPresent() && last.get().isBefore(clock.instant().minus(maxAge, ChronoUnit.DAYS))) {
                return Rejection.of("notSeasonPack", "Last episode in this season aired more than " + maxAge
                        + " days ago, season pack required.");
            }
        }
        return null;
    }

    private Rejection qualityAllowed(RemoteEpisode r, SearchCriteria c, Settings s) {
        return ProfileQualities.allowed(r.profile, r.parsed.quality.quality) ? null
                : Rejection.of("qualityNotWanted", r.parsed.quality.quality.name() + " is not wanted in profile");
    }

    private Rejection customFormatAllowed(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (r.customFormatScore >= r.profile.minFormatScore()) {
            return null;
        }
        return Rejection.of("customFormatMinimumScore", "Custom Formats " + names(r.customFormats) + " have score "
                + r.customFormatScore + " below Series profile minimum " + r.profile.minFormatScore());
    }

    private Rejection upgradeAllowed(RemoteEpisode r, SearchCriteria c, Settings s) {
        for (EpisodeFile f : fileOf(r)) {
            if (!Upgradable.isUpgradeAllowed(r.profile, f.quality(), formats.forFile(f, r.series), r.parsed.quality,
                    r.customFormats)) {
                return Rejection.of("qualityUpgradesDisabled",
                        "Existing file and the Quality profile does not allow upgrades");
            }
        }
        return null;
    }

    private Rejection upgradeDisk(RemoteEpisode r, SearchCriteria c, Settings s) {
        QualityProfile p = r.profile;
        for (EpisodeFile f : fileOf(r)) {
            List<CustomFormat> fileFormats = formats.forFile(f, r.series);
            if (!Upgradable.cutoffNotMet(p, f.quality(), fileFormats, r.parsed.quality)) {
                int cutoff = p.upgradeAllowed() ? p.cutoff() : ProfileQualities.firstAllowed(p).id();
                return Rejection.of("diskCutoffMet", "Existing file meets cutoff: " + ProfileQualities.name(p, cutoff));
            }
            Rejection reason = upgradeRejection("disk", "Existing file on disk", p, f.quality(), fileFormats, r, s);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private Rejection sameEpisodesGrab(RemoteEpisode r, SearchCriteria c, Settings s) {
        Set<Integer> ids = Set.copyOf(r.episodeIds());
        for (EpisodeFile f : fileOf(r)) {
            if (f.episodeIds().stream().anyMatch(e -> !ids.contains(e))) {
                return Rejection.of("existingFileHasMoreEpisodes",
                        "Episode file on disk contains more episodes than this release contains");
            }
        }
        return null;
    }

    private Rejection repack(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (!r.parsed.quality.revision.isRepack || "doNotPrefer".equals(s.downloadPropersAndRepacks())) {
            return null;
        }
        for (EpisodeFile f : fileOf(r)) {
            if (Upgradable.isRevisionUpgrade(f.quality(), r.parsed.quality)) {
                if ("doNotUpgrade".equals(s.downloadPropersAndRepacks())) {
                    return Rejection.of("repackDisabled", "Repack downloading is disabled");
                }
                if (f.releaseGroup() == null || f.releaseGroup().isBlank()) {
                    return Rejection.of("repackUnknownReleaseGroup",
                            "Unable to determine release group for the existing file");
                }
                if (r.parsed.releaseGroup == null || r.parsed.releaseGroup.isBlank()) {
                    return Rejection.of("repackUnknownReleaseGroup",
                            "Unable to determine release group for this release");
                }
                if (!f.releaseGroup().equalsIgnoreCase(r.parsed.releaseGroup)) {
                    return Rejection.of("repackReleaseGroupDoesNotMatch", "Release is a repack for a different "
                            + "release group. Release Group: " + r.parsed.releaseGroup + ". File release group: "
                            + f.releaseGroup());
                }
            }
        }
        return null;
    }

    private Rejection animeVersionUpgrade(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (!"anime".equals(r.series.seriesType()) || "doNotPrefer".equals(s.downloadPropersAndRepacks())) {
            return null;
        }
        for (EpisodeFile f : fileOf(r)) {
            if (Upgradable.isRevisionUpgrade(f.quality(), r.parsed.quality)) {
                if (f.releaseGroup() == null || f.releaseGroup().isBlank()) {
                    return Rejection.of("unknownReleaseGroup", "Existing release group is unknown");
                }
                if (r.parsed.releaseGroup == null || r.parsed.releaseGroup.isBlank()) {
                    return Rejection.of("unknownReleaseGroup", "Release group is unknown");
                }
                if (!f.releaseGroup().equals(r.parsed.releaseGroup)) {
                    return Rejection.of("releaseGroupDoesNotMatch", r.parsed.releaseGroup
                            + " does not match existing release group " + f.releaseGroup());
                }
            }
        }
        return null;
    }

    private Rejection releaseRestrictions(RemoteEpisode r, SearchCriteria c, Settings s) {
        String title = r.release.title();
        List<ReleaseProfile> all = profiles.enabledForTags(tags(r), r.release.indexerId());
        for (ReleaseProfile p : all) {
            if (p.required() != null && !p.required().isEmpty()
                    && p.required().stream().noneMatch(t -> TermMatcher.isMatch(t, title))) {
                return Rejection.of("mustContainMissing", "Does not contain one of the required terms: "
                        + String.join(", ", p.required()));
            }
        }
        for (ReleaseProfile p : all) {
            List<String> found = p.ignored() == null ? List.of()
                    : p.ignored().stream().filter(t -> TermMatcher.isMatch(t, title)).toList();
            if (!found.isEmpty()) {
                return Rejection.of("mustNotContainPresent", "Contains these ignored terms: "
                        + String.join(", ", found));
            }
        }
        return null;
    }

    private Rejection protocol(RemoteEpisode r, SearchCriteria c, Settings s) {
        return profiles.bestForTags(tags(r)).enableTorrent() ? null
                : Rejection.of("protocolDisabled", "Torrent is not enabled for this series");
    }

    private Rejection indexerTag(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (r.release.indexerId() == 0) {
            return null;
        }
        Set<Integer> indexerTags = indexers.find(r.release.indexerId()).map(Indexer::tags).orElse(Set.of());
        if (indexerTags != null && !indexerTags.isEmpty() && indexerTags.stream().noneMatch(tags(r)::contains)) {
            return Rejection.of("noMatchingTag", "Series tags do not match any of the indexer tags");
        }
        return null;
    }

    private Rejection blocklisted(RemoteEpisode r, SearchCriteria c, Settings s) {
        return blocklist.blocklisted(r.series.id(), r.release) ? Rejection.of("blocklisted", "Release is blocklisted")
                : null;
    }

    private Rejection queueSpec(RemoteEpisode r, SearchCriteria c, Settings s) {
        Set<Integer> ids = Set.copyOf(r.episodeIds());
        for (QueueView.Queued q : queue.queued()) {
            if (q.seriesId() != r.series.id() || q.failedPending()
                    || q.episodeIds().stream().noneMatch(ids::contains)) {
                continue;
            }
            if (!Upgradable.cutoffNotMet(r.profile, q.quality(), q.customFormats(), r.parsed.quality)) {
                return Rejection.of("queueCutoffMet", "Release in queue already meets cutoff: " + q.quality());
            }
            Rejection reason = upgradeRejection("queue", "Release in queue", r.profile, q.quality(), q.customFormats(),
                    r, s);
            if (reason != null) {
                return reason;
            }
            if (Upgradable.isRevisionUpgrade(q.quality(), r.parsed.quality)
                    && "doNotUpgrade".equals(s.downloadPropersAndRepacks())) {
                return Rejection.of("queuePropersDisabled", "Proper downloading is disabled");
            }
        }
        return null;
    }

    private Rejection alreadyImported(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (!s.enableCompletedDownloadHandling()) {
            return null;
        }
        for (Episode e : r.episodes) {
            if (files.ofEpisode(e.id()).isEmpty()) {
                continue;
            }
            List<History> h = history.byEpisode(e.id());
            Optional<History> grabbed = h.stream().filter(x -> HistoryService.GRABBED.equals(x.eventType()))
                    .findFirst();
            if (grabbed.isEmpty()) {
                continue;
            }
            Optional<History> imported = h.stream().filter(x -> HistoryService.DOWNLOAD_FOLDER_IMPORTED
                    .equals(x.eventType()) && Objects.equals(x.downloadId(), grabbed.get().downloadId())).findFirst();
            if (imported.isEmpty() || Objects.equals(grabbed.get().quality(), imported.get().quality())) {
                continue;
            }
            if (r.release.infoHash() != null && r.release.infoHash().equalsIgnoreCase(grabbed.get().downloadId())) {
                return Rejection.of("alreadyImportedSameHash",
                        "Has same torrent hash as a grabbed and imported release");
            }
            if (r.release.title().equalsIgnoreCase(grabbed.get().sourceTitle())) {
                return Rejection.of("alreadyImportedSameName",
                        "Has same release name as a grabbed and imported release");
            }
        }
        return null;
    }

    private Rejection historySpec(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c != null) {
            return null;
        }
        for (Episode e : r.episodes) {
            Optional<History> recent = history.mostRecentForEpisode(e.id());
            if (recent.isEmpty() || !HistoryService.GRABBED.equals(recent.get().eventType())) {
                continue;
            }
            History h = recent.get();
            boolean isRecent = h.date().isAfter(clock.instant().minus(12, ChronoUnit.HOURS));
            if (!isRecent && s.enableCompletedDownloadHandling()) {
                continue;
            }
            List<CustomFormat> hFormats = formats.forTitle(h.sourceTitle(), h.quality(), h.languages(),
                    number(h.data() == null ? null : h.data().get("size")), r.series);
            if (!Upgradable.cutoffNotMet(r.profile, h.quality(), hFormats, r.parsed.quality)) {
                return Rejection.of(isRecent ? "historyRecentCutoffMet" : "historyCdhDisabledCutoffMet",
                        (isRecent ? "Recent" : "CDH is disabled and") + " grab event in history already meets cutoff: "
                                + h.quality());
            }
            Rejection reason = upgradeRejection("history", (isRecent ? "Recent" : "CDH is disabled and")
                    + " grab event in history", r.profile, h.quality(), hFormats, r, s);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private Rejection proper(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c != null || "doNotPrefer".equals(s.downloadPropersAndRepacks())) {
            return null;
        }
        for (EpisodeFile f : fileOf(r)) {
            if (Upgradable.isRevisionUpgrade(f.quality(), r.parsed.quality)) {
                if ("doNotUpgrade".equals(s.downloadPropersAndRepacks())) {
                    return Rejection.of("propersDisabled", "Proper downloading is disabled");
                }
                if (f.dateAdded().isBefore(clock.instant().truncatedTo(ChronoUnit.DAYS).minus(7, ChronoUnit.DAYS))) {
                    return Rejection.of("properForOldFile", "Proper for old file");
                }
            }
        }
        return null;
    }

    private Rejection monitoredEpisode(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c != null && !c.monitoredEpisodesOnly()) {
            return null;
        }
        if (!r.series.monitored()) {
            return Rejection.of("seriesNotMonitored", "Series is not monitored");
        }
        long monitored = r.episodes.stream().filter(Episode::monitored).count();
        if (monitored == r.episodes.size()) {
            return null;
        }
        return Rejection.of("episodeNotMonitored", r.episodes.size() == 1 ? "Episode is not monitored"
                : "One or more episodes is not monitored");
    }

    private Rejection delay(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c != null && c.userInvokedSearch()) {
            return null;
        }
        DelayProfile dp = profiles.bestForTags(tags(r));
        int delay = dp.torrentDelay();
        if (delay == 0) {
            return null;
        }
        for (EpisodeFile f : fileOf(r)) {
            if (ProfileQualities.compare(r.profile, r.parsed.quality.quality.id(), f.quality().quality.id()) == 0
                    && r.parsed.quality.revision.compareTo(f.quality().revision) > 0) {
                return null;
            }
        }
        if (dp.bypassIfHighestQuality() && ProfileQualities.compare(r.profile, r.parsed.quality.quality.id(),
                ProfileQualities.lastAllowed(r.profile).id()) >= 0) {
            return null;
        }
        if (dp.bypassIfAboveCustomFormatScore() && r.customFormatScore >= dp.minimumCustomFormatScore()) {
            return null;
        }
        Optional<PendingRelease> oldest = pending.oldest(r.series.id(), r.episodeIds());
        if (oldest.isPresent() && oldest.get().release().ageMinutes(clock.instant()) > delay) {
            return null;
        }
        if (r.release.ageMinutes(clock.instant()) < delay) {
            return new Rejection("minimumAgeDelay", "Waiting for better quality release", true);
        }
        return null;
    }

    private Rejection seriesSpec(RemoteEpisode r, SearchCriteria c, Settings s) {
        return c != null && r.series.id() != c.series().id() ? Rejection.of("wrongSeries", "Wrong series") : null;
    }

    private Rejection seasonMatch(RemoteEpisode r, SearchCriteria c, Settings s) {
        return c != null && "season".equals(c.kind()) && c.seasonNumber() != r.parsed.seasonNumber
                ? Rejection.of("wrongSeason", "Wrong season") : null;
    }

    private Rejection episodeRequested(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c == null) {
            return null;
        }
        Set<Integer> wanted = c.episodes().stream().map(Episode::id).collect(Collectors.toSet());
        if (r.episodes.stream().anyMatch(e -> wanted.contains(e.id()))) {
            return null;
        }
        if (r.episodes.isEmpty()) {
            return Rejection.of("wrongEpisode", "Episode wasn't requested");
        }
        Episode first = r.episodes.get(0);
        Episode last = r.episodes.get(r.episodes.size() - 1);
        return Rejection.of("wrongEpisode", "Episode wasn't requested: " + first.seasonNumber() + "x"
                + first.episodeNumber() + (r.episodes.size() > 1 ? "-" + last.episodeNumber() : ""));
    }

    private Rejection singleEpisodeSearchMatch(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (c == null) {
            return null;
        }
        if ("single".equals(c.kind())) {
            if (c.seasonNumber() != r.parsed.seasonNumber) {
                return Rejection.of("wrongSeason", "Wrong season");
            }
            if (r.parsed.episodeNumbers.length == 0) {
                return Rejection.of("fullSeason", "Full season pack");
            }
            if (java.util.Arrays.stream(r.parsed.episodeNumbers).noneMatch(n -> n == c.episodeNumber())) {
                return Rejection.of("wrongEpisode", "Wrong episode");
            }
        }
        if ("anime".equals(c.kind()) && r.parsed.fullSeason) {
            return Rejection.of("fullSeason", "Full season pack");
        }
        return null;
    }

    private Rejection freeSpace(RemoteEpisode r, SearchCriteria c, Settings s) {
        if (s.skipFreeSpaceCheckWhenImporting() || r.series.path() == null) {
            return null;
        }
        long free;
        try {
            Path path = Path.of(r.series.path());
            while (path != null && !Files.exists(path)) {
                path = path.getParent();
            }
            if (path == null) {
                return null;
            }
            free = Files.getFileStore(path).getUsableSpace();
        } catch (IOException | RuntimeException e) {
            return null;
        }
        long remaining = free - r.release.size();
        if (remaining <= 0) {
            return Rejection.of("minimumFreeSpace", "Importing after download will exceed available disk space");
        }
        long minimum = s.minimumFreeSpaceWhenImporting() * MB;
        if (remaining < minimum) {
            return Rejection.of("minimumFreeSpace", "Not enough free space (" + size(minimum) + ") to import after "
                    + "download: " + size(remaining) + ". (Settings: Media Management: Minimum Free Space)");
        }
        return null;
    }

    /** The reason of {@link Upgradable#isUpgradable} as the rule's message, for the disk, the queue or history. */
    private Rejection upgradeRejection(String prefix, String subject, QualityProfile p,
                                       com.catacumbas69.catasonarr.quality.QualityModel current,
                                       List<CustomFormat> currentFormats, RemoteEpisode r, Settings s) {
        Upgradable.Reason reason = Upgradable.isUpgradable(p, current, currentFormats, r.parsed.quality,
                r.customFormats, s.downloadPropersAndRepacks());
        return switch (reason) {
            case NONE -> null;
            case BETTER_QUALITY -> Rejection.of(prefix + "HigherPreference", subject
                    + " is of equal or higher preference: " + current);
            case BETTER_REVISION -> Rejection.of(prefix + "HigherRevision", subject
                    + " is of equal or higher revision: " + current.revision);
            case QUALITY_CUTOFF -> Rejection.of(prefix + "CutoffMet", subject + " meets quality cutoff: "
                    + ProfileQualities.name(p, p.cutoff()));
            case CUSTOM_FORMAT_CUTOFF -> Rejection.of(prefix + "CustomFormatCutoffMet", subject
                    + " meets Custom Format cutoff: " + p.cutoffFormatScore());
            case CUSTOM_FORMAT_SCORE -> Rejection.of(prefix + "CustomFormatScore", subject
                    + " has an equal or higher Custom Format score: " + ProfileQualities.score(p, currentFormats));
            case MIN_CUSTOM_FORMAT_SCORE -> Rejection.of(prefix + "CustomFormatScoreIncrement", subject
                    + " has Custom Format score within Custom Format score increment: " + p.minUpgradeFormatScore());
            case UPGRADES_NOT_ALLOWED -> Rejection.of(prefix + "UpgradesNotAllowed", subject + " and Quality Profile '"
                    + p.name() + "' does not allow upgrades");
        };
    }

    // ── Sonarr's DownloadDecisionComparer ───────────────────────────────────

    private Comparator<DownloadDecision> comparator(Settings s) {
        Comparator<DownloadDecision> quality = Comparator.comparingInt(d -> ProfileQualities.index(
                d.remoteEpisode().profile, d.remoteEpisode().parsed.quality.quality.id(), false)[0]);
        if (!"doNotPrefer".equals(s.downloadPropersAndRepacks())) {
            quality = quality.thenComparing(d -> d.remoteEpisode().parsed.quality.revision);
        }
        return quality
                .thenComparingInt(d -> d.remoteEpisode().customFormatScore)
                .thenComparing(this::compareEpisodeCount)
                .thenComparing(Comparator.<DownloadDecision>comparingInt(d -> d.remoteEpisode().episodes.stream()
                        .mapToInt(Episode::episodeNumber).min().orElse(0)).reversed())
                .thenComparing(Comparator.<DownloadDecision>comparingInt(d -> d.remoteEpisode().release
                        .indexerPriority()).reversed())
                .thenComparingDouble(d -> logRound(d.remoteEpisode().release.seeders()))
                .thenComparingDouble(d -> logRound(d.remoteEpisode().release.peers()))
                .thenComparingLong(this::sizeScore);
    }

    private int compareEpisodeCount(DownloadDecision x, DownloadDecision y) {
        int pack = Boolean.compare(x.remoteEpisode().parsed.fullSeason, y.remoteEpisode().parsed.fullSeason);
        if (pack != 0) {
            return pack;
        }
        boolean anime = "anime".equals(x.remoteEpisode().series.seriesType())
                && "anime".equals(y.remoteEpisode().series.seriesType());
        int count = Integer.compare(x.remoteEpisode().episodes.size(), y.remoteEpisode().episodes.size());
        return anime ? count : -count;
    }

    private long sizeScore(DownloadDecision d) {
        RemoteEpisode r = d.remoteEpisode();
        QualityDefinition def = definition(r.parsed.quality.quality.id());
        long round = 200 * MB;
        if (def != null && def.preferredSize() != null && r.series.runtime() > 0) {
            long preferred = (long) (r.series.runtime() * def.preferredSize() * MB);
            return -Math.abs(Math.round((double) (r.release.size() - preferred) / round) * round);
        }
        return Math.round((double) r.release.size() / round) * round;
    }

    private static double logRound(Integer value) {
        return value == null || value <= 0 ? 0 : Math.round(Math.log10(value));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<EpisodeFile> fileOf(RemoteEpisode r) {
        List<EpisodeFile> out = new ArrayList<>();
        Set<Integer> seen = new java.util.HashSet<>();
        for (Episode e : r.episodes) {
            files.ofEpisode(e.id()).filter(f -> seen.add(f.id())).ifPresent(out::add);
        }
        return out;
    }

    private QualityDefinition definition(int quality) {
        return mongo.findAll(QualityDefinition.class).stream().filter(d -> d.quality() == quality).findFirst()
                .orElse(null);
    }

    private static Set<Integer> tags(RemoteEpisode r) {
        return r.series.tags() == null ? Set.of() : r.series.tags();
    }

    private static String names(List<CustomFormat> formats) {
        return formats.stream().map(CustomFormat::name).collect(Collectors.joining(", ", "[", "]"));
    }

    private static int number(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : (int) Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Bytes as Sonarr writes them, e.g. {@code 1.2 GB}.
     *
     * @param bytes the bytes
     * @return the text
     */
    public static String size(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (Math.abs(v) >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }
}
