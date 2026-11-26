package com.catacumbas69.catasonarr.download;

import com.catacumbas69.catasonarr.model.DownloadClient;
import com.catacumbas69.catasonarr.providers.ProviderResources;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.NotFoundException;
import com.catacumbas69.catasonarr.service.Sequences;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Catasonarr's download clients (orch.md decision 122), as Sonarr's {@code /api/v3/downloadclient}: qBittorrent and
 * Transmission, each with Catasonarr's own category. Passwords stay in MongoDB and are never shown.
 */
@Service
public class DownloadClientService {

    /** Sequence name. */
    public static final String SEQUENCE = "downloadclient";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final List<TorrentClient> implementations;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param implementations qBittorrent's and Transmission's
     * @param events events
     */
    public DownloadClientService(MongoTemplate mongo, Sequences sequences, List<TorrentClient> implementations,
                                 CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.implementations = implementations;
        this.events = events;
    }

    /**
     * Every client.
     *
     * @return the clients by id
     */
    public List<DownloadClient> all() {
        return mongo.find(new Query().with(Sort.by("id")), DownloadClient.class);
    }

    /**
     * A client.
     *
     * @param id the id
     * @return the client
     */
    public DownloadClient get(int id) {
        return find(id).orElseThrow(() -> new NotFoundException("Download client " + id));
    }

    /**
     * A client, if it exists.
     *
     * @param id the id
     * @return the client
     */
    public Optional<DownloadClient> find(int id) {
        return Optional.ofNullable(mongo.findById(id, DownloadClient.class));
    }

    /**
     * The client of a release, as Sonarr picks it: the indexer's own client if it has one, else the enabled
     * clients whose tags fit the series, by priority.
     *
     * @param seriesTags the series' tags
     * @param indexerClientId the indexer's client, or 0
     * @return the client, if any
     */
    public Optional<DownloadClient> pick(Set<Integer> seriesTags, int indexerClientId) {
        List<DownloadClient> enabled = all().stream().filter(DownloadClient::enable).toList();
        if (indexerClientId > 0) {
            return enabled.stream().filter(c -> c.id() == indexerClientId).findFirst();
        }
        List<DownloadClient> tagged = enabled.stream().filter(c -> c.tags() != null && !c.tags().isEmpty()
                && c.tags().stream().anyMatch(seriesTags::contains)).toList();
        List<DownloadClient> candidates = tagged.isEmpty()
                ? enabled.stream().filter(c -> c.tags() == null || c.tags().isEmpty()).toList() : tagged;
        return candidates.stream().min(Comparator.comparingInt(DownloadClient::priority)
                .thenComparingInt(DownloadClient::id));
    }

    /**
     * The implementation of a client.
     *
     * @param client the client
     * @return qBittorrent's or Transmission's
     */
    public TorrentClient implementation(DownloadClient client) {
        return implementations.stream().filter(i -> i.implementation().equalsIgnoreCase(client.implementation()))
                .findFirst().orElseThrow(() -> new IllegalStateException("No client " + client.implementation()));
    }

    /**
     * Adds a client, after testing it.
     *
     * @param body Sonarr's resource
     * @param forceSave keep it even if the test fails
     * @param actor who did it
     * @return the client
     */
    public DownloadClient add(Map<String, Object> body, boolean forceSave, String actor) {
        DownloadClient candidate = ProviderResources.client(0, body, null);
        if (!forceSave) {
            test(candidate);
        }
        DownloadClient saved = withId(candidate, sequences.next(SEQUENCE));
        mongo.insert(saved);
        events.change("downloadclient", "added", subject(saved), actor, null, describe(saved));
        return saved;
    }

    /**
     * Replaces a client, after testing it.
     *
     * @param id the id
     * @param body Sonarr's resource
     * @param forceSave keep it even if the test fails
     * @param actor who did it
     * @return the client
     */
    public DownloadClient update(int id, Map<String, Object> body, boolean forceSave, String actor) {
        DownloadClient before = get(id);
        DownloadClient saved = ProviderResources.client(id, body, before);
        if (!forceSave) {
            test(saved);
        }
        mongo.save(saved);
        events.change("downloadclient", "updated", subject(saved), actor, describe(before), describe(saved));
        return saved;
    }

    /**
     * Deletes a client.
     *
     * @param id the id
     * @param actor who did it
     */
    public void delete(int id, String actor) {
        DownloadClient before = get(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), DownloadClient.class);
        events.change("downloadclient", "deleted", subject(before), actor, describe(before), null);
    }

    /**
     * Tests what Sonarr's API sends, without keeping it.
     *
     * @param body Sonarr's resource
     */
    public void test(Map<String, Object> body) {
        Object id = body.get("id");
        DownloadClient old = id instanceof Number n && n.intValue() > 0 ? find(n.intValue()).orElse(null) : null;
        test(ProviderResources.client(old == null ? 0 : old.id(), body, old));
    }

    /**
     * Tests a client: it answers, signs in and has Catasonarr's category.
     *
     * @param client the client
     */
    public void test(DownloadClient client) {
        try {
            implementation(client).test(client);
        } catch (TorrentClient.ClientException e) {
            throw new BadRequestException("host", e.getMessage());
        }
    }

    private static DownloadClient withId(DownloadClient c, int id) {
        return new DownloadClient(id, c.name(), c.implementation(), c.settings(), c.enable(), c.priority(),
                c.removeCompletedDownloads(), c.removeFailedDownloads(), c.tags());
    }

    private static String subject(DownloadClient c) {
        return c.name() + " (" + c.id() + ")";
    }

    /**
     * A client as events show it: without its password or API key.
     *
     * @param c the client
     * @return its fields
     */
    public static Map<String, Object> describe(DownloadClient c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("implementation", c.implementation());
        m.put("host", c.settings().get("host"));
        m.put("port", c.settings().get("port"));
        m.put("username", c.settings().get("username"));
        m.put("tvCategory", c.settings().get("tvCategory"));
        m.put("enable", c.enable());
        m.put("priority", c.priority());
        m.put("tags", c.tags());
        return m;
    }
}
