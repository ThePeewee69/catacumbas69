package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a release's or a file's name says (Sonarr's {@code ParsedEpisodeInfo}, GPL-3.0, orch.md decision 122): the
 * series, the season and episodes (or the air date, or absolute numbers), the quality, the languages and the group.
 */
public final class ParsedEpisodeInfo {

    /** The name parsed, without its extension. */
    public String releaseTitle;
    /** The series' title as written. */
    public String seriesTitle;
    /** The series' title, with and without year, and every title it gives. */
    public SeriesTitleInfo seriesTitleInfo;
    /** The quality. */
    public QualityModel quality;
    /** The season. */
    public int seasonNumber;
    /** The episodes of the season. */
    public int[] episodeNumbers = new int[0];
    /** The absolute numbers (anime). */
    public int[] absoluteEpisodeNumbers = new int[0];
    /** Absolute numbers of specials, e.g. 13.5. */
    public BigDecimal[] specialAbsoluteEpisodeNumbers = new BigDecimal[0];
    /** A daily release's air date, {@code yyyy-MM-dd}, or {@code null}. */
    public String airDate;
    /** The languages. */
    public List<Language> languages = new ArrayList<>();
    /** A whole season. */
    public boolean fullSeason;
    /** Part of a season (a pack with a part number). */
    public boolean isPartialSeason;
    /** Several seasons. */
    public boolean isMultiSeason;
    /** A season's extras or subtitles pack. */
    public boolean isSeasonExtra;
    /** One episode in parts. */
    public boolean isSplitEpisode;
    /** A mini-series: episodes without a season. */
    public boolean isMiniSeries;
    /** A special. */
    public boolean special;
    /** The release group. */
    public String releaseGroup;
    /** An anime release's hash, e.g. {@code ABCD1234}. */
    public String releaseHash;
    /** The part of a partial season. */
    public int seasonPart;
    /** What follows the season and episode, where quality and languages are read. */
    public String releaseTokens;
    /** The part of a daily episode, or {@code null}. */
    public Integer dailyPart;

    /**
     * Whether it is a daily release (by air date).
     *
     * @return whether it has an air date
     */
    public boolean isDaily() {
        return airDate != null && !airDate.isBlank();
    }

    /**
     * Whether it is numbered absolutely (anime).
     *
     * @return whether it has absolute numbers
     */
    public boolean isAbsoluteNumbering() {
        return absoluteEpisodeNumbers.length > 0;
    }

    /**
     * Whether it may be a special.
     *
     * @return Sonarr's guess
     */
    public boolean isPossibleSpecialEpisode() {
        boolean noTitle = seriesTitle == null || seriesTitle.isBlank();
        return (isBlank(airDate) && noTitle && (episodeNumbers.length == 0 || seasonNumber == 0))
                || (!noTitle && special)
                || (episodeNumbers.length == 1 && episodeNumbers[0] == 0);
    }

    /**
     * Whether it may be a season's special numbered episode 0.
     *
     * @return whether it is SxxE00 of a season other than 0
     */
    public boolean isPossibleSceneSeasonSpecial() {
        return seasonNumber != 0 && episodeNumbers.length == 1 && episodeNumbers[0] == 0;
    }

    /**
     * What it holds.
     *
     * @return one episode, several, a season pack, or unknown
     */
    public ReleaseType releaseType() {
        if (episodeNumbers.length > 1 || absoluteEpisodeNumbers.length > 1) {
            return ReleaseType.MULTI_EPISODE;
        }
        if (episodeNumbers.length == 1 || absoluteEpisodeNumbers.length == 1) {
            return ReleaseType.SINGLE_EPISODE;
        }
        return fullSeason ? ReleaseType.SEASON_PACK : ReleaseType.UNKNOWN;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String toString() {
        String episodes = "[Unknown Episode]";
        if (isDaily() && episodeNumbers.length == 0) {
            episodes = airDate;
        } else if (fullSeason) {
            episodes = String.format("Season %02d", seasonNumber);
        } else if (episodeNumbers.length > 0) {
            episodes = String.format("S%02dE%s", seasonNumber, Arrays.stream(episodeNumbers)
                    .mapToObj(e -> String.format("%02d", e)).collect(Collectors.joining("-")));
        } else if (absoluteEpisodeNumbers.length > 0) {
            episodes = Arrays.stream(absoluteEpisodeNumbers).mapToObj(e -> String.format("%03d", e))
                    .collect(Collectors.joining("-"));
        } else if (special) {
            episodes = seasonNumber != 0 ? String.format("[Unknown Season %02d Special]", seasonNumber)
                    : "[Unknown Special]";
        }
        return seriesTitle + " - " + episodes + " " + quality;
    }
}
