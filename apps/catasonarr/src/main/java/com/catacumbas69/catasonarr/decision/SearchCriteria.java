package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.Series;
import java.util.List;

/**
 * What a search looks for (Sonarr's search criteria): one episode, a season, a daily episode, an anime episode or a
 * special.
 *
 * @param series the series
 * @param episodes the episodes wanted
 * @param kind {@code single}, {@code season}, {@code daily}, {@code anime}, {@code animeSeason} or {@code special}
 * @param seasonNumber the season
 * @param episodeNumber the episode, for a single one
 * @param airDate the air date, {@code yyyy-MM-dd}, for a daily one
 * @param absoluteEpisodeNumber the absolute number, for anime
 * @param userInvokedSearch asked by someone (not the automatic searches)
 * @param interactiveSearch the interactive search, which grabs nothing on its own
 * @param monitoredEpisodesOnly only monitored episodes are wanted
 */
public record SearchCriteria(Series series, List<Episode> episodes, String kind, int seasonNumber, int episodeNumber,
                             String airDate, Integer absoluteEpisodeNumber, boolean userInvokedSearch,
                             boolean interactiveSearch, boolean monitoredEpisodesOnly) {
}
