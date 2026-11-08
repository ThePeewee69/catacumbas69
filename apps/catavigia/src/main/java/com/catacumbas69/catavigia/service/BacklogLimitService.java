package com.catacumbas69.catavigia.service;

import com.catacumbas69.catavigia.model.BacklogLimit;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Backlog limits set per subscription (orch.md decision 70), kept in {@code catavigia_backlog_limits}. Each change
 * is published as {@code limit.changed}; removing one goes back to the global default.
 */
@Service
public class BacklogLimitService {

    private final MongoTemplate mongoTemplate;
    private final AlertEvents events;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for the limits
     * @param events publishes {@code limit.changed}
     * @param clock time source
     */
    public BacklogLimitService(MongoTemplate mongoTemplate, AlertEvents events, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Returns every limit, by topic and subscription.
     *
     * @return the limits
     */
    public List<BacklogLimit> list() {
        return mongoTemplate.findAll(BacklogLimit.class).stream()
                .sorted(Comparator.comparing(BacklogLimit::topic).thenComparing(BacklogLimit::subscription))
                .toList();
    }

    /**
     * Returns every limit by id.
     *
     * @return {@code <topic>|<subscription>} to limit
     */
    public Map<String, BacklogLimit> byId() {
        return mongoTemplate.findAll(BacklogLimit.class).stream()
                .collect(Collectors.toMap(BacklogLimit::id, Function.identity(), (a, b) -> a));
    }

    /**
     * Sets a subscription's own limit.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param limit messages above which {@code backlog} opens
     * @param who client address
     * @return the limit stored
     */
    public BacklogLimit set(String topic, String subscription, long limit, String who) {
        String id = BacklogLimit.idOf(topic, subscription);
        BacklogLimit before = mongoTemplate.findById(id, BacklogLimit.class);
        BacklogLimit after = new BacklogLimit(id, topic, subscription, limit, who, clock.instant());
        mongoTemplate.save(after);
        if (before == null || before.limit() != limit) {
            events.limitChanged(id, who, before == null ? null : before.limit(), limit);
        }
        return after;
    }

    /**
     * Removes a subscription's own limit, so the global default applies again.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @param who client address
     * @return {@code true} if there was one
     */
    public boolean remove(String topic, String subscription, String who) {
        String id = BacklogLimit.idOf(topic, subscription);
        BacklogLimit removed = mongoTemplate.findAndRemove(Query.query(Criteria.where("_id").is(id)),
                BacklogLimit.class);
        if (removed == null) {
            return false;
        }
        events.limitChanged(id, who, removed.limit(), null);
        return true;
    }
}
