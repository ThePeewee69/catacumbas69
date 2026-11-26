package com.catacumbas69.catasonarr.indexers;

import com.catacumbas69.catasonarr.model.Indexer;
import com.catacumbas69.catasonarr.providers.ProviderResources;
import com.catacumbas69.catasonarr.service.BadRequestException;
import com.catacumbas69.catasonarr.service.CatasonarrEvents;
import com.catacumbas69.catasonarr.service.NotFoundException;
import com.catacumbas69.catasonarr.service.Sequences;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The indexers Prowlarr pushes (orch.md decision 122), kept as Sonarr keeps them. Each change is tested against the
 * feed unless {@code forceSave} (as Prowlarr sends), and is an event; the API key never leaves Catasonarr.
 */
@Service
public class IndexerService {

    /** Sequence name. */
    public static final String SEQUENCE = "indexer";

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final TorznabClient torznab;
    private final CatasonarrEvents events;

    /**
     * Creates the service.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param torznab the feeds
     * @param events events
     */
    public IndexerService(MongoTemplate mongo, Sequences sequences, TorznabClient torznab, CatasonarrEvents events) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.torznab = torznab;
        this.events = events;
    }

    /**
     * Every indexer.
     *
     * @return the indexers by id
     */
    public List<Indexer> all() {
        return mongo.find(new Query().with(Sort.by("id")), Indexer.class);
    }

    /**
     * An indexer.
     *
     * @param id the id
     * @return the indexer
     */
    public Indexer get(int id) {
        return find(id).orElseThrow(() -> new NotFoundException("Indexer " + id));
    }

    /**
     * An indexer, if it exists.
     *
     * @param id the id
     * @return the indexer
     */
    public Optional<Indexer> find(int id) {
        return Optional.ofNullable(mongo.findById(id, Indexer.class));
    }

    /**
     * Adds an indexer.
     *
     * @param body Sonarr's resource
     * @param forceSave keep it even if the feed does not answer
     * @param actor who did it
     * @return the indexer
     */
    public Indexer add(Map<String, Object> body, boolean forceSave, String actor) {
        Indexer candidate = ProviderResources.indexer(0, body, null);
        if (!forceSave) {
            test(candidate);
        }
        Indexer saved = withId(candidate, sequences.next(SEQUENCE));
        mongo.insert(saved);
        events.change("indexer", "added", subject(saved), actor, null, describe(saved));
        return saved;
    }

    /**
     * Replaces an indexer.
     *
     * @param id the id
     * @param body Sonarr's resource
     * @param forceSave keep it even if the feed does not answer
     * @param actor who did it
     * @return the indexer
     */
    public Indexer update(int id, Map<String, Object> body, boolean forceSave, String actor) {
        Indexer before = get(id);
        Indexer saved = ProviderResources.indexer(id, body, before);
        if (!forceSave) {
            test(saved);
        }
        mongo.save(saved);
        torznab.forget(id);
        events.change("indexer", "updated", subject(saved), actor, describe(before), describe(saved));
        return saved;
    }

    /**
     * Deletes an indexer.
     *
     * @param id the id
     * @param actor who did it
     */
    public void delete(int id, String actor) {
        Indexer before = get(id);
        mongo.remove(Query.query(Criteria.where("id").is(id)), Indexer.class);
        torznab.forget(id);
        events.change("indexer", "deleted", subject(before), actor, describe(before), null);
    }

    /**
     * Tests what Sonarr's API sends, without keeping it.
     *
     * @param body Sonarr's resource
     */
    public void test(Map<String, Object> body) {
        Object id = body.get("id");
        Indexer old = id instanceof Number n && n.intValue() > 0 ? find(n.intValue()).orElse(null) : null;
        test(ProviderResources.indexer(old == null ? 0 : old.id(), body, old));
    }

    /**
     * Tests an indexer: its capabilities and one page of its RSS, as Sonarr's test.
     *
     * @param indexer the indexer
     */
    public void test(Indexer indexer) {
        try {
            TorznabClient.Capabilities caps = torznab.capabilities(new Indexer(0, indexer.name(),
                    indexer.implementation(), indexer.settings(), true, true, true, indexer.priority(), 0, 0,
                    indexer.tags()));
            if (!caps.tvSearch()) {
                throw new BadRequestException("baseUrl", "The indexer does not search TV (no tv-search in its caps)");
            }
            torznab.rss(indexer);
        } catch (TorznabClient.TorznabException e) {
            throw new BadRequestException("baseUrl", "Unable to connect to indexer: " + e.getMessage());
        }
    }

    private static Indexer withId(Indexer i, int id) {
        return new Indexer(id, i.name(), i.implementation(), i.settings(), i.enableRss(), i.enableAutomaticSearch(),
                i.enableInteractiveSearch(), i.priority(), i.seasonSearchMaximumSingleEpisodeAge(),
                i.downloadClientId(), i.tags());
    }

    private static String subject(Indexer i) {
        return i.name() + " (" + i.id() + ")";
    }

    /**
     * An indexer as events show it: without its API key.
     *
     * @param i the indexer
     * @return its fields
     */
    public static Map<String, Object> describe(Indexer i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", i.name());
        m.put("baseUrl", i.settings().get("baseUrl"));
        m.put("categories", i.settings().get("categories"));
        m.put("minimumSeeders", i.settings().get("minimumSeeders"));
        m.put("enableRss", i.enableRss());
        m.put("enableAutomaticSearch", i.enableAutomaticSearch());
        m.put("enableInteractiveSearch", i.enableInteractiveSearch());
        m.put("priority", i.priority());
        m.put("tags", i.tags());
        return m;
    }
}
