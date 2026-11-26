package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Sonarr's episode history (orch.md decision 122): one record per episode for every grab, import, failure,
 * deletion, rename and ignored download. It ties a download (its hash) to the series and episodes it was grabbed
 * for, so a restart loses nothing.
 */
@Service
public class HistoryService {

    /** Sequence name. */
    public static final String SEQUENCE = "history";

    /** Grabbed by Catasonarr. */
    public static final String GRABBED = "grabbed";
    /** Imported from a download. */
    public static final String DOWNLOAD_FOLDER_IMPORTED = "downloadFolderImported";
    /** Found in the series' folder (rescan). */
    public static final String SERIES_FOLDER_IMPORTED = "seriesFolderImported";
    /** The download failed. */
    public static final String DOWNLOAD_FAILED = "downloadFailed";
    /** The file was deleted. */
    public static final String EPISODE_FILE_DELETED = "episodeFileDeleted";
    /** The file was renamed. */
    public static final String EPISODE_FILE_RENAMED = "episodeFileRenamed";
    /** The download was ignored (removed from the queue without blocklisting). */
    public static final String DOWNLOAD_IGNORED = "downloadIgnored";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param clock the time
     */
    public HistoryService(MongoTemplate mongo, Sequences sequences, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.clock = clock;
    }

    /**
     * Records an event for each episode.
     *
     * @param eventType e.g. {@link #GRABBED}
     * @param seriesId the series
     * @param episodeIds the episodes
     * @param sourceTitle the release's or file's name
     * @param quality the quality
     * @param languages the languages
     * @param downloadId the download's id, or {@code null}
     * @param data more
     * @return the records
     */
    public List<History> record(String eventType, int seriesId, Collection<Integer> episodeIds, String sourceTitle,
                                QualityModel quality, List<Language> languages, String downloadId,
                                Map<String, String> data) {
        Instant now = clock.instant();
        List<History> all = new ArrayList<>();
        for (int episodeId : episodeIds) {
            History h = new History(sequences.next(SEQUENCE), episodeId, seriesId, sourceTitle, quality,
                    languages == null ? List.of() : languages, now, eventType, downloadId, data);
            mongo.insert(h);
            all.add(h);
        }
        return all;
    }

    /**
     * One page of the history, newest first.
     *
     * @param page from 1
     * @param pageSize records per page
     * @param eventType only this event, or {@code null}
     * @param episodeId only this episode, or {@code null}
     * @param downloadId only this download, or {@code null}
     * @param seriesIds only these series, or {@code null}
     * @return the records
     */
    public List<History> page(int page, int pageSize, String eventType, Integer episodeId, String downloadId,
                              Collection<Integer> seriesIds) {
        Query q = filter(eventType, episodeId, downloadId, seriesIds).with(Sort.by(Sort.Direction.DESC, "date"))
                .skip((long) Math.max(0, page - 1) * pageSize).limit(pageSize);
        return mongo.find(q, History.class);
    }

    /**
     * How many records a filter has.
     *
     * @param eventType only this event, or {@code null}
     * @param episodeId only this episode, or {@code null}
     * @param downloadId only this download, or {@code null}
     * @param seriesIds only these series, or {@code null}
     * @return the count
     */
    public long count(String eventType, Integer episodeId, String downloadId, Collection<Integer> seriesIds) {
        return mongo.count(filter(eventType, episodeId, downloadId, seriesIds), History.class);
    }

    /**
     * A series' history, newest first.
     *
     * @param seriesId the series
     * @param seasonNumber only this season's episodes, or {@code null}
     * @param episodeIds the season's episodes when a season is given
     * @return the records
     */
    public List<History> bySeries(int seriesId, Integer seasonNumber, Collection<Integer> episodeIds) {
        Criteria c = Criteria.where("seriesId").is(seriesId);
        if (seasonNumber != null) {
            c = c.and("episodeId").in(episodeIds);
        }
        return mongo.find(Query.query(c).with(Sort.by(Sort.Direction.DESC, "date")), History.class);
    }

    /**
     * The records since a time, oldest first.
     *
     * @param since the time
     * @param eventType only this event, or {@code null}
     * @return the records
     */
    public List<History> since(Instant since, String eventType) {
        Criteria c = Criteria.where("date").gte(since);
        if (eventType != null) {
            c = c.and("eventType").is(eventType);
        }
        return mongo.find(Query.query(c).with(Sort.by("date")), History.class);
    }

    /**
     * An episode's history, newest first.
     *
     * @param episodeId the episode
     * @return the records
     */
    public List<History> byEpisode(int episodeId) {
        return mongo.find(Query.query(Criteria.where("episodeId").is(episodeId))
                .with(Sort.by(Sort.Direction.DESC, "date")), History.class);
    }

    /**
     * An episode's newest record.
     *
     * @param episodeId the episode
     * @return the record, if any
     */
    public Optional<History> mostRecentForEpisode(int episodeId) {
        return byEpisode(episodeId).stream().findFirst();
    }

    /**
     * A download's records, newest first.
     *
     * @param downloadId the download's id
     * @return the records
     */
    public List<History> byDownloadId(String downloadId) {
        return mongo.find(Query.query(Criteria.where("downloadId").is(downloadId))
                .with(Sort.by(Sort.Direction.DESC, "date")), History.class);
    }

    /**
     * A download's grab records.
     *
     * @param downloadId the download's id
     * @return the grabs
     */
    public List<History> grabbed(String downloadId) {
        return byDownloadId(downloadId).stream().filter(h -> GRABBED.equals(h.eventType())).toList();
    }

    /**
     * A record.
     *
     * @param id the id
     * @return the record
     */
    public History get(int id) {
        History h = mongo.findById(id, History.class);
        if (h == null) {
            throw new NotFoundException("History " + id);
        }
        return h;
    }

    /**
     * Forgets a series' history.
     *
     * @param seriesId the series
     */
    public void deleteBySeries(int seriesId) {
        mongo.remove(Query.query(Criteria.where("seriesId").is(seriesId)), History.class);
    }

    private static Query filter(String eventType, Integer episodeId, String downloadId,
                                Collection<Integer> seriesIds) {
        Criteria c = new Criteria();
        List<Criteria> all = new ArrayList<>();
        if (eventType != null) {
            all.add(Criteria.where("eventType").is(eventType));
        }
        if (episodeId != null) {
            all.add(Criteria.where("episodeId").is(episodeId));
        }
        if (downloadId != null) {
            all.add(Criteria.where("downloadId").is(downloadId));
        }
        if (seriesIds != null) {
            all.add(Criteria.where("seriesId").in(seriesIds));
        }
        return all.isEmpty() ? new Query() : Query.query(c.andOperator(all.toArray(Criteria[]::new)));
    }
}
