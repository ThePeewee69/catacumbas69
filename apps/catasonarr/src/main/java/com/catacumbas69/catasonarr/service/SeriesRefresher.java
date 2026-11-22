package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.metadata.MetadataUnavailableException;
import com.catacumbas69.catasonarr.metadata.TvMazeClient;
import com.catacumbas69.catasonarr.model.Series;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the series' metadata every {@code catasonarr.metadata.refresh-interval} (12 h, as Sonarr), in this pod
 * (orch.md decision 109: one replica, {@code @Scheduled}). TVmaze's list of changed shows lets it skip the series
 * that did not change, so a refresh costs a few calls, not three per series.
 */
@Component
public class SeriesRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(SeriesRefresher.class);
    private static final Duration WEEK = Duration.ofDays(7);

    private final SeriesService series;
    private final TvMazeClient tvmaze;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    /**
     * Creates the refresher.
     *
     * @param series the series
     * @param tvmaze TVmaze, for its list of changed shows
     * @param clock the time
     */
    public SeriesRefresher(SeriesService series, TvMazeClient tvmaze, Clock clock) {
        this.series = series;
        this.tvmaze = tvmaze;
        this.clock = clock;
    }

    /**
     * The 12-hourly refresh: every series TVmaze changed since its last refresh, and every series not refreshed for
     * a week.
     */
    @Scheduled(initialDelayString = "${catasonarr.metadata.refresh-initial-delay}",
            fixedDelayString = "${catasonarr.metadata.refresh-interval}")
    public void scheduled() {
        refreshAll(false, CatasonarrEvents.SELF);
    }

    /**
     * Refreshes every series; one refresh at a time.
     *
     * @param force refresh even the series TVmaze did not change
     * @param actor who asked
     * @return how many series were refreshed, or -1 when a refresh was already running
     */
    public int refreshAll(boolean force, String actor) {
        if (!running.tryLock()) {
            LOG.info("A refresh is already running; this one is skipped");
            return -1;
        }
        try {
            List<Series> all = series.all();
            Map<Integer, Long> changed = force || all.isEmpty() ? Map.of() : tvmaze.updates("week");
            Instant weekAgo = clock.instant().minus(WEEK);
            int done = 0;
            int failed = 0;
            for (Series s : all) {
                boolean stale = s.lastInfoSync() == null || s.lastInfoSync().isBefore(weekAgo);
                Long changedAt = changed.get(s.tvMazeId());
                boolean changedSince = !stale && changedAt != null
                        && Instant.ofEpochSecond(changedAt).isAfter(s.lastInfoSync());
                if (force || stale || changedSince) {
                    try {
                        series.refresh(s.id(), actor);
                        done++;
                    } catch (MetadataUnavailableException | DataAccessException | NotFoundException e) {
                        failed++;
                        LOG.warn("Series {} ({}) not refreshed: {}", s.title(), s.id(), e.getMessage());
                    }
                }
            }
            LOG.info("Refresh done: {} of {} series refreshed, {} failed", done, all.size(), failed);
            return done;
        } catch (MetadataUnavailableException | DataAccessException e) {
            LOG.warn("Refresh not done, trying again at the next one: {}", e.getMessage());
            return 0;
        } finally {
            running.unlock();
        }
    }
}
