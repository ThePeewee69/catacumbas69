package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.model.RemotePathMapping;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Sonarr's remote path mappings (orch.md decision 122): a download client's folder as Catasonarr sees it. Today
 * qBittorrent and Catasonarr both see the downloads as {@code /downloads}, so none is needed.
 */
@Service
public class RemotePathMappingService {

    /** Sequence name. */
    public static final String SEQUENCE = "remotepathmapping";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param events events
     */
    public RemotePathMappingService(MongoTemplate mongo, Sequences sequences, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.events = events;
    }

    /**
     * Every mapping.
     *
     * @return the mappings
     */
    public List<RemotePathMapping> all() {
        return mongo.find(new Query().with(Sort.by("id")), RemotePathMapping.class);
    }

    /**
     * A mapping.
     *
     * @param id the id
     * @return the mapping
     */
    public RemotePathMapping get(int id) {
        RemotePathMapping m = mongo.findById(id, RemotePathMapping.class);
        if (m == null) {
            throw new NotFoundException("Remote path mapping " + id);
        }
        return m;
    }

    /**
     * Adds or replaces a mapping; both paths are absolute and end with {@code /}.
     *
     * @param id the id, or 0 to add
     * @param m the mapping
     * @param actor who did it
     * @return the mapping saved
     */
    public RemotePathMapping save(int id, RemotePathMapping m, String actor) {
        if (m.host() == null || m.host().isBlank()) {
            throw new BadRequestException("host", "'Host' must not be empty.");
        }
        if (m.localPath() == null || !m.localPath().startsWith("/")) {
            throw new BadRequestException("localPath", "'Local Path' must be an absolute path");
        }
        if (m.remotePath() == null || m.remotePath().isBlank()) {
            throw new BadRequestException("remotePath", "'Remote Path' must not be empty.");
        }
        RemotePathMapping saved = new RemotePathMapping(id == 0 ? sequences.next(SEQUENCE) : id, m.host().strip(),
                slash(m.remotePath().strip()), slash(m.localPath().strip()));
        mongo.save(saved);
        events.change("remotepathmapping", id == 0 ? "added" : "updated", saved.host() + " " + saved.remotePath(),
                actor, null, Map.of("mapping", saved));
        return saved;
    }

    /**
     * Deletes a mapping.
     *
     * @param id the id
     * @param actor who did it
     */
    public void delete(int id, String actor) {
        RemotePathMapping before = get(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), RemotePathMapping.class);
        events.change("remotepathmapping", "deleted", before.host() + " " + before.remotePath(), actor,
                Map.of("mapping", before), null);
    }

    /**
     * A download client's path as Catasonarr sees it, as Sonarr's {@code RemapRemoteToLocal}.
     *
     * @param host the client's host
     * @param remotePath the path the client gives
     * @return the local path
     */
    public String remoteToLocal(String host, String remotePath) {
        if (remotePath == null) {
            return null;
        }
        for (RemotePathMapping m : all()) {
            if (m.host().equalsIgnoreCase(host) && (remotePath + "/").startsWith(m.remotePath())) {
                String rest = remotePath.length() >= m.remotePath().length()
                        ? remotePath.substring(m.remotePath().length()) : "";
                return m.localPath() + rest;
            }
        }
        return remotePath;
    }

    /**
     * A local path as a download client sees it.
     *
     * @param host the client's host
     * @param localPath the path here
     * @return the client's path
     */
    public String localToRemote(String host, String localPath) {
        if (localPath == null) {
            return null;
        }
        for (RemotePathMapping m : all()) {
            if (m.host().equalsIgnoreCase(host) && (localPath + "/").startsWith(m.localPath())) {
                String rest = localPath.length() >= m.localPath().length()
                        ? localPath.substring(m.localPath().length()) : "";
                return m.remotePath() + rest;
            }
        }
        return localPath;
    }

    private static String slash(String path) {
        return path.endsWith("/") || path.endsWith("\\") ? path : path + "/";
    }
}
