package com.catacumbas69.catasonarr.service;

import com.catacumbas69.catasonarr.config.CatasonarrProperties;
import com.catacumbas69.catasonarr.model.Blocklist;
import com.catacumbas69.catasonarr.model.Command;
import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.Episode;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.History;
import com.catacumbas69.catasonarr.model.PendingRelease;
import com.catacumbas69.catasonarr.model.QualityDefinition;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.model.RootFolder;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Tag;
import com.catacumbas69.catasonarr.quality.Qualities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Prepares Catasonarr's collections when the app is ready (MONGO-3, orch.md decisions 112 and 122): the indexes,
 * and Sonarr's default quality definitions and profiles while there are none (the import from Sonarr's database
 * replaces them).
 * Tried in the background; a failure is logged and tried again at the next start or by {@link #ensure()}.
 */
@Component
public class CatasonarrSetup implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CatasonarrSetup.class);

    private final MongoTemplate mongo;
    private final Sequences sequences;
    private final Duration commandKeep;
    private volatile boolean done;

    /**
     * Creates the setup.
     *
     * @param mongo MongoDB
     * @param sequences ids
     * @param properties how long commands are kept
     */
    public CatasonarrSetup(MongoTemplate mongo, Sequences sequences, CatasonarrProperties properties) {
        this.mongo = mongo;
        this.sequences = sequences;
        this.commandKeep = properties.commands().keep();
    }

    /**
     * Tries once in the background.
     *
     * @param event the application is ready
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Thread.ofVirtual().name("catasonarr-setup").start(() -> {
            try {
                ensure();
            } catch (RuntimeException e) {
                LOG.warn("MongoDB not prepared yet ({}); tried again at the next start", e.getMessage());
            }
        });
    }

    /** Creates the indexes and the defaults once; later calls do nothing. Throws if MongoDB fails. */
    public synchronized void ensure() {
        if (done) {
            return;
        }
        mongo.indexOps(Series.class).createIndex(asc("tvdbId").unique().named("series_tvdb"));
        mongo.indexOps(Series.class).createIndex(asc("titleSlug").unique().named("series_slug"));
        mongo.indexOps(Series.class).createIndex(asc("path").named("series_path"));
        mongo.indexOps(Episode.class).createIndex(asc("seriesId").on("seasonNumber", Sort.Direction.ASC)
                .on("episodeNumber", Sort.Direction.ASC).named("episodes_series"));
        mongo.indexOps(RootFolder.class).createIndex(asc("path").unique().named("rootfolders_path"));
        mongo.indexOps(QualityProfile.class).createIndex(asc("name").unique().named("qualityprofiles_name"));
        mongo.indexOps(CustomFormat.class).createIndex(asc("name").unique().named("customformats_name"));
        mongo.indexOps(Tag.class).createIndex(asc("label").unique().named("tags_label"));
        mongo.indexOps(Command.class).createIndex(asc("queued").expire(commandKeep).named("commands_ttl"));
        mongo.indexOps(EpisodeFile.class).createIndex(asc("seriesId").named("episodefiles_series"));
        mongo.indexOps(EpisodeFile.class).createIndex(asc("episodeIds").named("episodefiles_episodes"));
        mongo.indexOps(History.class).createIndex(new Index().on("date", Sort.Direction.DESC).named("history_date"));
        mongo.indexOps(History.class).createIndex(asc("seriesId").named("history_series"));
        mongo.indexOps(History.class).createIndex(asc("episodeId").named("history_episode"));
        mongo.indexOps(History.class).createIndex(asc("downloadId").named("history_download"));
        mongo.indexOps(Blocklist.class).createIndex(asc("seriesId").named("blocklist_series"));
        mongo.indexOps(Blocklist.class).createIndex(asc("torrentInfoHash").named("blocklist_hash"));
        mongo.indexOps(PendingRelease.class).createIndex(asc("seriesId").named("pending_series"));
        seedDefaults();
        done = true;
        LOG.info("Catasonarr collections ready (commands kept for {} h)", commandKeep.toHours());
    }

    private void seedDefaults() {
        if (mongo.count(new Query(), QualityDefinition.class) == 0) {
            List<QualityDefinition> definitions = new ArrayList<>();
            int id = sequences.reserve(QualityService.DEFINITION, Qualities.DEFAULTS.size());
            for (Qualities.Default d : Qualities.DEFAULTS) {
                definitions.add(new QualityDefinition(id++, d.quality().id(), d.quality().name(), d.minSize(),
                        d.maxSize(), d.preferredSize()));
            }
            mongo.insert(definitions, QualityDefinition.class);
            LOG.info("Sonarr's default quality definitions added ({})", definitions.size());
        }
        if (mongo.count(new Query(), QualityProfile.class) == 0) {
            List<QualityProfile> defaults = QualityService.defaultProfiles();
            int id = sequences.reserve(QualityService.PROFILE, defaults.size());
            List<QualityProfile> profiles = new ArrayList<>();
            for (QualityProfile p : defaults) {
                profiles.add(new QualityProfile(id++, p.name(), p.upgradeAllowed(), p.cutoff(), p.items(),
                        p.minFormatScore(), p.cutoffFormatScore(), p.minUpgradeFormatScore(), p.formatItems()));
            }
            mongo.insert(profiles, QualityProfile.class);
            LOG.info("Sonarr's default quality profiles added ({})", profiles.size());
        }
    }

    private static Index asc(String field) {
        return new Index().on(field, Sort.Direction.ASC);
    }
}
