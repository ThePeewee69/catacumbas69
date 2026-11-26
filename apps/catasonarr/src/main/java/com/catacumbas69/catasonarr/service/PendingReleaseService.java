package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.PendingRelease;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Releases held back (orch.md decision 122), as Sonarr's pending releases: by a delay profile, or because no
 * download client could take them. The RSS sync and the queue's check grab them when they may go.
 */
@Service
public class PendingReleaseService {

    /** Sequence name. */
    public static final String SEQUENCE = "pendingrelease";

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
    public PendingReleaseService(MongoTemplate mongo, Sequences sequences, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.clock = clock;
    }

    /**
     * Every pending release.
     *
     * @return the releases
     */
    public List<PendingRelease> all() {
        return mongo.findAll(PendingRelease.class);
    }

    /**
     * A pending release.
     *
     * @param id the id
     * @return the release
     */
    public PendingRelease get(int id) {
        PendingRelease p = mongo.findById(id, PendingRelease.class);
        if (p == null) {
            throw new NotFoundException("Pending release " + id);
        }
        return p;
    }

    /**
     * Holds a release back, unless it already is.
     *
     * @param seriesId the series
     * @param episodeIds its episodes
     * @param release the release
     * @param reason {@code delay}, {@code downloadClientUnavailable} or {@code fallback}
     */
    public void add(int seriesId, List<Integer> episodeIds, ReleaseInfo release, String reason) {
        boolean known = mongo.exists(Query.query(Criteria.where("seriesId").is(seriesId).and("title")
                .is(release.title()).and("release.indexerId").is(release.indexerId())), PendingRelease.class);
        if (!known) {
            mongo.insert(new PendingRelease(sequences.next(SEQUENCE), seriesId, episodeIds, release.title(),
                    clock.instant(), release, reason));
        }
    }

    /**
     * The oldest pending release of some episodes, for the delay.
     *
     * @param seriesId the series
     * @param episodeIds the episodes
     * @return the oldest, if any
     */
    public Optional<PendingRelease> oldest(int seriesId, Collection<Integer> episodeIds) {
        return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId).and("episodeIds").in(episodeIds)),
                PendingRelease.class).stream()
                .min((a, b) -> a.release().publishDate().compareTo(b.release().publishDate()));
    }

    /**
     * Forgets pending releases.
     *
     * @param ids the ids
     */
    public void remove(Collection<Integer> ids) {
        mongo.remove(Query.query(Criteria.where("id").in(ids)), PendingRelease.class);
    }

    /**
     * Forgets the pending releases of some episodes, once one of them was grabbed.
     *
     * @param seriesId the series
     * @param episodeIds the episodes
     */
    public void removeForEpisodes(int seriesId, Collection<Integer> episodeIds) {
        mongo.remove(Query.query(Criteria.where("seriesId").is(seriesId).and("episodeIds").in(episodeIds)),
                PendingRelease.class);
    }

    /**
     * Forgets a series' pending releases.
     *
     * @param seriesId the series
     */
    public void deleteBySeries(int seriesId) {
        mongo.remove(Query.query(Criteria.where("seriesId").is(seriesId)), PendingRelease.class);
    }
}
