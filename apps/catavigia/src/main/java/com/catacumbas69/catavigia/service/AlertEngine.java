package com.catacumbas69.catavigia.service;

import static com.catacumbas69.catavigia.model.Schema.CHECK;
import static com.catacumbas69.catavigia.model.Schema.CLEAR;
import static com.catacumbas69.catavigia.model.Schema.DETAILS;
import static com.catacumbas69.catavigia.model.Schema.KEY;
import static com.catacumbas69.catavigia.model.Schema.LAST_ROUND;
import static com.catacumbas69.catavigia.model.Schema.LAST_SEEN_AT;
import static com.catacumbas69.catavigia.model.Schema.SEEN;
import static com.catacumbas69.catavigia.model.Schema.SUBJECT;
import static com.catacumbas69.catavigia.model.Schema.TYPE;

import com.catacumbas69.catavigia.checks.Check;
import com.catacumbas69.catavigia.checks.Finding;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import com.catacumbas69.catavigia.model.Observation;
import com.catacumbas69.catavigia.model.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Turns what a check saw in one round into open and closed alerts (ADR 0015, orch.md decision 75).
 *
 * <ol>
 *   <li>Each problem seen moves its observation ({@code <type>|<subject>}) forward: one more round seen, none
 *       clear. A problem seen for {@code alerts.debounce} rounds in a row opens its alert; an open alert just gets
 *       the new details and time.</li>
 *   <li>Every other observation of the check moves the other way: one more round clear. After
 *       {@code alerts.debounce} clear rounds its alert, if open, moves to the archive and the observation is
 *       removed.</li>
 *   <li>An observation moves only for a round newer than the last one it counted, so a task delivered again
 *       after newer rounds ran changes nothing (orch.md decision 79).</li>
 *   <li>Opening is an insert under a unique key and closing a delete: with several replicas only the one whose
 *       write succeeded publishes the event (ADR 0012).</li>
 * </ol>
 */
@Service
public class AlertEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEngine.class);

    private final MongoTemplate mongoTemplate;
    private final AlertEvents events;
    private final Clock clock;

    /**
     * Creates the engine.
     *
     * @param mongoTemplate template for Catavigia's collections
     * @param events publishes alert events
     * @param clock time source
     */
    public AlertEngine(MongoTemplate mongoTemplate, AlertEvents events, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Applies one round of a check.
     *
     * @param check the check
     * @param round start of the round
     * @param findings what it saw
     * @param debounce rounds in a row needed to open or close
     * @return how many alerts this call opened and closed
     */
    public Result apply(Check check, Instant round, List<Finding> findings, int debounce) {
        Map<String, Finding> seen = new LinkedHashMap<>();
        findings.forEach(finding -> seen.put(Schema.key(finding.type(), finding.subject()), finding));
        int opened = 0;
        int closed = 0;
        int stale = 0;
        for (Map.Entry<String, Finding> entry : seen.entrySet()) {
            Finding finding = entry.getValue();
            Observation observation = advance(entry.getKey(), finding.type(), finding.subject(), check.name(), round,
                    true);
            if (observation == null) {
                stale++;
                continue;
            }
            if (refresh(entry.getKey(), finding)) {
                continue;
            }
            if (observation.seen() >= debounce && open(entry.getKey(), finding)) {
                opened++;
            }
        }
        Query others = Query.query(Criteria.where(CHECK).is(check.name()).and(TYPE).in(check.types())
                .and("_id").nin(seen.keySet()));
        for (Observation other : mongoTemplate.find(others, Observation.class)) {
            Observation observation = advance(other.key(), other.type(), other.subject(), check.name(), round,
                    false);
            if (observation == null) {
                stale++;
                continue;
            }
            if (observation.clear() >= debounce) {
                if (close(other.key())) {
                    closed++;
                }
                mongoTemplate.remove(Query.query(Criteria.where("_id").is(other.key()).and(CLEAR).gte(debounce)),
                        Observation.class);
            }
        }
        return new Result(findings.size(), opened, closed, stale);
    }

    /**
     * Moves an observation forward by one round, if the round is newer than the last it counted.
     *
     * @return the observation after the change, or {@code null} if a newer round already counted it
     */
    private Observation advance(String key, String type, String subject, String check, Instant round,
                                boolean problem) {
        Query newer = Query.query(Criteria.where("_id").is(key).and(LAST_ROUND).lt(round));
        Update update = new Update().set(TYPE, type).set(SUBJECT, subject).set(CHECK, check).set(LAST_ROUND, round);
        if (problem) {
            update.inc(SEEN, 1).set(CLEAR, 0);
        } else {
            update.inc(CLEAR, 1).set(SEEN, 0);
        }
        try {
            return mongoTemplate.findAndModify(newer, update,
                    FindAndModifyOptions.options().upsert(problem).returnNew(true), Observation.class);
        } catch (DuplicateKeyException e) {
            // the observation exists with a round as new or newer: this task is late
            return null;
        }
    }

    /** Updates an open alert with the round's details; returns {@code false} if none is open. */
    private boolean refresh(String key, Finding finding) {
        return mongoTemplate.updateFirst(Query.query(Criteria.where(KEY).is(key)),
                new Update().set(LAST_SEEN_AT, clock.instant()).set(DETAILS, finding.details()), Alert.class)
                .getMatchedCount() > 0;
    }

    private boolean open(String key, Finding finding) {
        Instant now = clock.instant();
        Alert alert = new Alert(UUID.randomUUID().toString(), key, finding.type(), finding.subject(),
                AlertTypes.severity(finding.type()), finding.details(), now, now, null, null);
        try {
            mongoTemplate.insert(alert);
        } catch (DuplicateKeyException e) {
            // another replica opened it first
            refresh(key, finding);
            return false;
        }
        LOG.warn("Alert opened: {} {} {}", alert.severity(), alert.type(), alert.subject());
        events.opened(alert);
        return true;
    }

    private boolean close(String key) {
        Alert alert = mongoTemplate.findOne(Query.query(Criteria.where(KEY).is(key)), Alert.class);
        if (alert == null) {
            return false;
        }
        ArchivedAlert archived = ArchivedAlert.of(alert, clock.instant());
        mongoTemplate.save(archived);
        if (mongoTemplate.remove(Query.query(Criteria.where("_id").is(alert.id())), Alert.class)
                .getDeletedCount() == 0) {
            return false;
        }
        LOG.info("Alert closed after {} s: {} {}", archived.durationSeconds(), alert.type(), alert.subject());
        events.closed(archived);
        return true;
    }

    /**
     * What one round of a check did.
     *
     * @param findings problems the check saw
     * @param opened alerts this call opened
     * @param closed alerts this call closed
     * @param stale observations not moved, because a newer round had already counted them
     */
    public record Result(int findings, int opened, int closed, int stale) {
    }
}
