package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Season;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Which episodes and seasons are monitored when a series is added, translated from Sonarr 4.0.20's
 * {@code EpisodeMonitoredService} (GPL-3.0, orch.md decisions 109 and 112). Until the import of files exists, no
 * episode has a file.
 */
public final class MonitorRules {

    /**
     * The result: the episodes and the seasons with their new flags.
     *
     * @param episodes the episodes
     * @param seasons the seasons
     */
    public record Result(List<Episode> episodes, List<Season> seasons) {
    }

    private static final Duration RECENT = Duration.ofDays(90);

    private MonitorRules() {
    }

    /**
     * Applies Sonarr's {@code addOptions.monitor}.
     *
     * @param monitor {@code all}, {@code future}, {@code missing}, {@code existing}, {@code pilot},
     *                {@code firstSeason}, {@code lastSeason}, {@code latestSeason}, {@code recent},
     *                {@code monitorSpecials}, {@code unmonitorSpecials}, {@code none}, {@code skip}, or
     *                {@code unknown}/{@code null} (then the seasons' flags decide, as Sonarr's v2 clients and
     *                Overseerr expect)
     * @param ignoreEpisodesWithoutFiles for {@code unknown}: leave the aired episodes unmonitored
     * @param status the series' status
     * @param episodes the episodes, each monitored as its season
     * @param seasons the seasons, as asked
     * @param now the time
     * @return the episodes and seasons with their flags
     */
    public static Result apply(String monitor, boolean ignoreEpisodesWithoutFiles, String status,
                               List<Episode> episodes, List<Season> seasons, Instant now) {
        String m = monitor == null ? "unknown" : monitor;
        if ("skip".equals(m)) {
            return new Result(episodes, seasons);
        }
        if ("unknown".equals(m)) {
            return legacy(ignoreEpisodesWithoutFiles, episodes, seasons, now);
        }
        int firstSeason = seasons.stream().mapToInt(Season::seasonNumber).filter(n -> n > 0).min().orElse(0);
        int lastSeason = seasons.stream().mapToInt(Season::seasonNumber).max().orElse(0);
        List<Episode> result = switch (m) {
            case "all" -> toggle(episodes, e -> e.seasonNumber() > 0);
            case "future" -> toggle(episodes, e -> e.seasonNumber() > 0
                    && (e.airDateUtc() == null || !e.airDateUtc().isBefore(now)));
            case "missing" -> toggle(episodes, e -> e.seasonNumber() > 0);
            case "existing" -> toggle(episodes, e -> false);
            case "pilot" -> toggle(episodes, e -> e.seasonNumber() > 0 && e.seasonNumber() == firstSeason
                    && e.episodeNumber() == 1);
            case "firstSeason" -> toggle(episodes, e -> e.seasonNumber() > 0 && e.seasonNumber() == firstSeason);
            case "lastSeason", "latestSeason" -> toggle(episodes,
                    e -> e.seasonNumber() > 0 && e.seasonNumber() == lastSeason);
            case "recent" -> toggle(episodes, e -> e.seasonNumber() > 0 && (e.airDateUtc() == null
                    || e.airDateUtc().isBefore(now) && e.airDateUtc().isAfter(now.minus(RECENT))
                    || e.airDateUtc().isAfter(now)));
            case "monitorSpecials" -> set(episodes, e -> e.seasonNumber() == 0, true);
            case "unmonitorSpecials" -> set(episodes, e -> e.seasonNumber() == 0, false);
            case "none" -> toggle(episodes, e -> false);
            default -> throw new BadRequestException("addOptions.monitor", "Unknown monitor type: " + m);
        };
        Set<Integer> monitoredSeasons = result.stream().filter(Episode::monitored).map(Episode::seasonNumber)
                .collect(Collectors.toSet());
        boolean airing = "continuing".equals(status) || "upcoming".equals(status);
        List<Season> newSeasons = new ArrayList<>();
        for (Season season : seasons) {
            int n = season.seasonNumber();
            boolean monitored;
            if (n > 0 && n == lastSeason && ("all".equals(m) || "future".equals(m) && airing)) {
                monitored = true;
            } else if (n == firstSeason && "pilot".equals(m)) {
                monitored = false;
            } else {
                monitored = monitoredSeasons.contains(n);
            }
            newSeasons.add(new Season(n, monitored));
        }
        return new Result(result, newSeasons);
    }

    private static Result legacy(boolean ignoreEpisodesWithoutFiles, List<Episode> episodes, List<Season> seasons,
                                 Instant now) {
        List<Episode> result = set(episodes, e -> e.airDateUtc() != null && e.airDateUtc().isBefore(now),
                !ignoreEpisodesWithoutFiles);
        int lastSeason = seasons.stream().mapToInt(Season::seasonNumber).max().orElse(0);
        List<Season> newSeasons = new ArrayList<>();
        for (Season season : seasons) {
            int n = season.seasonNumber();
            boolean monitored = season.monitored();
            if (!monitored) {
                result = set(result, e -> e.seasonNumber() == n, false);
            }
            if (n < lastSeason && result.stream().filter(e -> e.seasonNumber() == n).noneMatch(Episode::monitored)) {
                monitored = false;
            }
            newSeasons.add(new Season(n, monitored));
        }
        return new Result(result, newSeasons);
    }

    private static List<Episode> toggle(List<Episode> episodes, Predicate<Episode> monitored) {
        return episodes.stream().map(e -> with(e, monitored.test(e))).toList();
    }

    private static List<Episode> set(List<Episode> episodes, Predicate<Episode> which, boolean monitored) {
        return episodes.stream().map(e -> which.test(e) ? with(e, monitored) : e).toList();
    }

    /**
     * Returns an episode with another monitored flag.
     *
     * @param e the episode
     * @param monitored the flag
     * @return the copy
     */
    public static Episode with(Episode e, boolean monitored) {
        return new Episode(e.id(), e.seriesId(), e.tvMazeId(), e.seasonNumber(), e.episodeNumber(),
                e.absoluteEpisodeNumber(), e.title(), e.overview(), e.airDate(), e.airDateUtc(), e.runtime(),
                monitored, e.images());
    }
}
