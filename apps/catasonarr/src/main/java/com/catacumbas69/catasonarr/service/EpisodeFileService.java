package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.EpisodeFile;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The episodes' files (orch.md decision 122): what is on disk for each episode. Only the records; moving, renaming
 * and deleting the files themselves is {@code media}'s.
 */
@Service
public class EpisodeFileService {

    /** Sequence name. */
    public static final String SEQUENCE = "episodefile";

    private final MongoTemplate mongo;
    private final Sequences sequences;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     */
    public EpisodeFileService(MongoTemplate mongo, Sequences sequences) {
        this.mongo = mongo;
        this.sequences = sequences;
    }

    /**
     * A series' files.
     *
     * @param seriesId the series
     * @return its files
     */
    public List<EpisodeFile> bySeries(int seriesId) {
        return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId)).with(Sort.by("id")), EpisodeFile.class);
    }

    /**
     * Files of several series, or of all.
     *
     * @param seriesIds the series, or {@code null} for all
     * @return the files
     */
    public List<EpisodeFile> bySeries(Collection<Integer> seriesIds) {
        Query q = seriesIds == null ? new Query() : Query.query(Criteria.where("seriesId").in(seriesIds));
        return mongo.find(q, EpisodeFile.class);
    }

    /**
     * A file.
     *
     * @param id the id
     * @return the file
     */
    public EpisodeFile get(int id) {
        EpisodeFile f = mongo.findById(id, EpisodeFile.class);
        if (f == null) {
            throw new NotFoundException("Episode file " + id);
        }
        return f;
    }

    /**
     * Files by id.
     *
     * @param ids the ids
     * @return the files found
     */
    public List<EpisodeFile> byIds(Collection<Integer> ids) {
        return mongo.find(Query.query(Criteria.where("id").in(ids)), EpisodeFile.class);
    }

    /**
     * The file of an episode, if it has one.
     *
     * @param episodeId the episode
     * @return its file
     */
    public Optional<EpisodeFile> ofEpisode(int episodeId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("episodeIds").is(episodeId)),
                EpisodeFile.class));
    }

    /**
     * Each episode's file id, for the episodes of some series.
     *
     * @param seriesIds the series, or {@code null} for all
     * @return episode id to file
     */
    public Map<Integer, EpisodeFile> byEpisode(Collection<Integer> seriesIds) {
        Map<Integer, EpisodeFile> map = new HashMap<>();
        for (EpisodeFile f : bySeries(seriesIds)) {
            f.episodeIds().forEach(e -> map.put(e, f));
        }
        return map;
    }

    /**
     * Gives a new file its id.
     *
     * @return the id
     */
    public int nextId() {
        return sequences.next(SEQUENCE);
    }

    /**
     * Keeps a file; the episodes it holds leave any other file.
     *
     * @param file the file
     * @return the file
     */
    public EpisodeFile save(EpisodeFile file) {
        Query others = Query.query(Criteria.where("seriesId").is(file.seriesId()).and("id").ne(file.id())
                .and("episodeIds").in(file.episodeIds()));
        for (EpisodeFile other : mongo.find(others, EpisodeFile.class)) {
            List<Integer> left = other.episodeIds().stream().filter(e -> !file.episodeIds().contains(e)).toList();
            if (left.isEmpty()) {
                delete(other.id());
            } else {
                mongo.save(new EpisodeFile(other.id(), other.seriesId(), other.seasonNumber(), left,
                        other.relativePath(), other.size(), other.dateAdded(), other.sceneName(), other.releaseGroup(),
                        other.quality(), other.languages(), other.mediaInfo(), other.originalFilePath(),
                        other.indexerFlags(), other.releaseType()));
            }
        }
        mongo.save(file);
        return file;
    }

    /**
     * Forgets a file (the file on disk is not touched).
     *
     * @param id the id
     */
    public void delete(int id) {
        mongo.remove(Query.query(Criteria.where("id").is(id)), EpisodeFile.class);
    }

    /**
     * Forgets a series' files.
     *
     * @param seriesId the series
     */
    public void deleteBySeries(int seriesId) {
        mongo.remove(Query.query(Criteria.where("seriesId").is(seriesId)), EpisodeFile.class);
    }
}
