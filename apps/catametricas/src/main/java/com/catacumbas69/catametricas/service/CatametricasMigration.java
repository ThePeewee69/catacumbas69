package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.Schema;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoNamespace;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the collections from before phase 4 to Catametricas' own names (ADR 0010): each {@code contario_*}
 * collection is renamed ({@code renameCollection}: instant, indexes kept) to its {@code catametricas_*} name, and
 * the lease ({@code contario_locks}) and the last-run status ({@code contario_status}) are dropped.
 *
 * <p>Runs before the first index creation and the first write, and does nothing once there is nothing left to
 * move. If both the old and the new collection exist, the old one is left alone with a warning (merge or drop
 * it by hand). A rename that another instance did first counts as done.
 */
@Component
public class CatametricasMigration {

    private static final Logger LOG = LoggerFactory.getLogger(CatametricasMigration.class);

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the migration.
     *
     * @param mongoTemplate template for the collections
     */
    public CatametricasMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Renames and drops what is left from before phase 4. Throws if MongoDB fails, so it is tried again later.
     *
     * @return the number of collections renamed
     */
    public int migrate() {
        Set<String> existing = mongoTemplate.getCollectionNames();
        int renamed = 0;
        for (Map.Entry<String, String> move : new TreeMap<>(Schema.LEGACY_RENAMES).entrySet()) {
            String from = move.getKey();
            String to = move.getValue();
            if (!existing.contains(from)) {
                continue;
            }
            if (existing.contains(to)) {
                LOG.warn("Both {} and {} exist; {} is left alone, merge or drop it by hand", from, to, from);
                continue;
            }
            try {
                mongoTemplate.getCollection(from).renameCollection(
                        new MongoNamespace(mongoTemplate.getDb().getName(), to));
                renamed++;
                LOG.info("Renamed MongoDB collection {} to {}", from, to);
            } catch (MongoCommandException e) {
                if (mongoTemplate.collectionExists(to) && !mongoTemplate.collectionExists(from)) {
                    LOG.info("{} was renamed to {} by another instance", from, to);
                } else {
                    throw e;
                }
            }
        }
        for (String legacy : new TreeSet<>(Schema.LEGACY_DROPS)) {
            if (existing.contains(legacy)) {
                mongoTemplate.dropCollection(legacy);
                LOG.info("Dropped MongoDB collection {} (no longer used)", legacy);
            }
        }
        return renamed;
    }
}
