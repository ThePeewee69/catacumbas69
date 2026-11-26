package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.parser.ParsedEpisodeInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * A release with what it was matched to (Sonarr's {@code RemoteEpisode}): its parsed name, its series and episodes,
 * its custom formats and their score in the series' profile.
 */
public final class RemoteEpisode {

    /** What its name says. */
    public ParsedEpisodeInfo parsed;
    /** Its series, or {@code null}. */
    public Series series;
    /** The series' quality profile, or {@code null}. */
    public QualityProfile profile;
    /** Its episodes. */
    public List<Episode> episodes = new ArrayList<>();
    /** The release. */
    public ReleaseInfo release;
    /** Its custom formats. */
    public List<CustomFormat> customFormats = new ArrayList<>();
    /** Their score in the series' profile. */
    public int customFormatScore;
    /** Its languages. */
    public List<Language> languages = new ArrayList<>();
    /** Whether a search asked for one of its episodes. */
    public boolean episodeRequested;
    /**
     * As Sonarr's {@code ReleaseSourceType}: {@code Rss}, {@code Search}, {@code UserInvokedSearch} or
     * {@code InteractiveSearch}.
     */
    public String releaseSource = "Rss";

    /**
     * Its episodes' ids.
     *
     * @return the ids
     */
    public List<Integer> episodeIds() {
        return episodes.stream().map(Episode::id).toList();
    }
}
