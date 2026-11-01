package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.model.Setting;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the settings from {@code pulsario_settings} to {@code catapulsar_settings} with their current ids
 * (ADR 0010, phase 3): copy what is missing, check that every old setting has its copy, then drop the old
 * collection. A setting already in the new collection is newer and is kept. Idempotent and safe with several
 * replicas: a second run finds nothing to do. The old collection is kept, with a warning, if it holds an id this
 * version does not know.
 */
@Component
public class SettingsMigration {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsMigration.class);

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the migration.
     *
     * @param mongoTemplate MongoDB access
     */
    public SettingsMigration(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Runs the migration.
     *
     * @return settings copied now
     * @throws org.springframework.dao.DataAccessException if MongoDB fails; nothing is dropped then
     */
    public int migrate() {
        if (!mongoTemplate.collectionExists(Setting.LEGACY_COLLECTION)) {
            return 0;
        }
        List<Setting> legacy = mongoTemplate.findAll(Setting.class, Setting.LEGACY_COLLECTION);
        int copied = 0;
        boolean complete = true;
        for (Setting old : legacy) {
            Optional<String> id = Setting.fromLegacyId(old.getId());
            if (id.isEmpty()) {
                LOG.warn("Unknown setting {} in {}, not migrated", old.getId(), Setting.LEGACY_COLLECTION);
                complete = false;
                continue;
            }
            if (copy(id.get(), old)) {
                copied++;
            }
            complete &= mongoTemplate.findById(id.get(), Setting.class) != null;
        }
        if (complete) {
            mongoTemplate.dropCollection(Setting.LEGACY_COLLECTION);
            LOG.info("{} migrated to {} ({} copied now) and dropped", Setting.LEGACY_COLLECTION, Setting.COLLECTION,
                    copied);
        } else {
            LOG.warn("{} kept: not every setting could be migrated", Setting.LEGACY_COLLECTION);
        }
        return copied;
    }

    private boolean copy(String id, Setting old) {
        if (mongoTemplate.findById(id, Setting.class) != null) {
            return false;
        }
        try {
            mongoTemplate.insert(new Setting(id, old.getValue(), old.getUpdatedAt(), old.getUpdatedBy()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
