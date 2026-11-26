package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.Blocklist;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.ReleaseInfo;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Sonarr's blocklist (orch.md decision 122): releases not to grab again, by their torrent's hash, else by their name
 * on the same indexer.
 */
@Service
public class BlocklistService {

    /** Sequence name. */
    public static final String SEQUENCE = "blocklist";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final CatasonarrEvents events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param events events
     * @param clock the time
     */
    public BlocklistService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events, Clock clock) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Whether a release is blocklisted for a series, as Sonarr's {@code Blocklisted} for torrents.
     *
     * @param seriesId the series
     * @param release the release
     * @return whether it is
     */
    public boolean blocklisted(int seriesId, ReleaseInfo release) {
        if (release.infoHash() != null && !release.infoHash().isBlank()) {
            return blocklistedHash(seriesId, release.infoHash());
        }
        List<Blocklist> byTitle = mongo.find(Query.query(Criteria.where("seriesId").is(seriesId)), Blocklist.class)
                .stream().filter(b -> b.sourceTitle().equalsIgnoreCase(release.title())).toList();
        return byTitle.stream().anyMatch(b -> b.indexer() == null || b.indexer().isBlank()
                || b.indexer().equalsIgnoreCase(release.indexer()));
    }

    /**
     * Whether a torrent's hash is blocklisted for a series.
     *
     * @param seriesId the series
     * @param hash the hash
     * @return whether it is
     */
    public boolean blocklistedHash(int seriesId, String hash) {
        return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId).and("torrentInfoHash").ne(null)),
                Blocklist.class).stream().anyMatch(b -> b.torrentInfoHash().equalsIgnoreCase(hash));
    }

    /**
     * Blocks a release.
     *
     * @param seriesId the series
     * @param episodeIds its episodes
     * @param release the release
     * @param quality its quality
     * @param languages its languages
     * @param releaseType Sonarr's release type number
     * @param message why
     * @param actor who did it
     * @return the entry
     */
    public Blocklist block(int seriesId, List<Integer> episodeIds, ReleaseInfo release, QualityModel quality,
                           List<Language> languages, int releaseType, String message, String actor) {
        Blocklist b = new Blocklist(sequences.next(SEQUENCE), seriesId, episodeIds, release.title(), quality,
                languages, clock.instant(), release.publishDate(), release.size(), "torrent", release.indexer(),
                release.indexerFlags(), releaseType, message,
                release.infoHash() == null ? null : release.infoHash().toUpperCase(Locale.ROOT));
        mongo.insert(b);
        events.action("blocklist", "added", b.sourceTitle(), actor, Map.of("id", b.id(), "seriesId", seriesId,
                "message", message == null ? "" : message));
        return b;
    }

    /**
     * One page, newest first.
     *
     * @param page from 1
     * @param pageSize per page
     * @param seriesIds only these series, or {@code null}
     * @return the entries
     */
    public List<Blocklist> page(int page, int pageSize, Collection<Integer> seriesIds) {
        Query q = seriesIds == null ? new Query() : Query.query(Criteria.where("seriesId").in(seriesIds));
        return mongo.find(q.with(Sort.by(Sort.Direction.DESC, "date")).skip((long) Math.max(0, page - 1) * pageSize)
                .limit(pageSize), Blocklist.class);
    }

    /**
     * How many entries.
     *
     * @param seriesIds only these series, or {@code null}
     * @return the count
     */
    public long count(Collection<Integer> seriesIds) {
        return mongo.count(seriesIds == null ? new Query() : Query.query(Criteria.where("seriesId").in(seriesIds)),
                Blocklist.class);
    }

    /**
     * A series' entries.
     *
     * @param seriesId the series
     * @return the entries
     */
    public List<Blocklist> bySeries(int seriesId) {
        return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId)), Blocklist.class);
    }

    /**
     * Removes entries.
     *
     * @param ids the ids
     * @param actor who did it
     */
    public void delete(Collection<Integer> ids, String actor) {
        mongo.remove(Query.query(Criteria.where("id").in(ids)), Blocklist.class);
        events.action("blocklist", "deleted", "blocklist " + ids, actor, Map.of("ids", ids));
    }

    /**
     * Forgets a series' entries.
     *
     * @param seriesId the series
     */
    public void deleteBySeries(int seriesId) {
        mongo.remove(Query.query(Criteria.where("seriesId").is(seriesId)), Blocklist.class);
    }
}
