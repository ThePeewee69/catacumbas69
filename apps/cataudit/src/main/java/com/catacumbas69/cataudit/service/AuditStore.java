package com.catacumbas69.cataudit.service;

import com.catacumbas69.cataudit.model.AuditEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reads and writes {@code cataudit_events}. Writes are inserts keyed by event id, so storing the same event
 * twice has no effect (ADR 0012).
 */
@Service
public class AuditStore {

    private final MongoTemplate mongoTemplate;

    /**
     * Creates the store.
     *
     * @param mongoTemplate MongoDB access
     */
    public AuditStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Stores an event unless one with the same id exists.
     *
     * @param event the event
     * @return {@code true} if stored, {@code false} if it was already there
     * @throws org.springframework.dao.DataAccessException if MongoDB fails
     */
    public boolean insert(AuditEvent event) {
        try {
            mongoTemplate.insert(event);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Returns one event.
     *
     * @param id event id
     * @return the event, if any
     */
    public Optional<AuditEvent> get(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, AuditEvent.class));
    }

    /**
     * Searches, newest first.
     *
     * @param query filters and page
     * @return the page
     */
    public AuditPage find(AuditQuery query) {
        Query filter = filter(query);
        long total = mongoTemplate.count(filter, AuditEvent.class);
        Query page = filter(query)
                .with(Sort.by(Sort.Order.desc("time"), Sort.Order.desc("_id")))
                .skip((long) query.page() * query.size())
                .limit(query.size());
        return new AuditPage(mongoTemplate.find(page, AuditEvent.class), query.page(), query.size(), total);
    }

    /**
     * Returns the values in use, for the filter lists of the UI.
     *
     * @param field {@code app}, {@code entity} or {@code action}
     * @return distinct values, sorted
     */
    public List<String> distinct(String field) {
        List<String> values = new ArrayList<>(mongoTemplate.findDistinct(new Query(), field, AuditEvent.class,
                String.class));
        values.removeIf(value -> value == null || value.isBlank());
        values.sort(String::compareTo);
        return values;
    }

    /**
     * Builds the MongoDB filter of a search.
     *
     * @param query filters
     * @return the query, without sort or page
     */
    static Query filter(AuditQuery query) {
        Criteria criteria = new Criteria();
        List<Criteria> parts = new ArrayList<>();
        equal(parts, "app", query.app());
        equal(parts, "entity", query.entity());
        equal(parts, "action", query.action());
        equal(parts, "actor", query.actor());
        if (hasText(query.subject())) {
            parts.add(Criteria.where("subject").regex("^" + Pattern.quote(query.subject().strip())));
        }
        if (query.from() != null || query.to() != null) {
            Criteria time = Criteria.where("time");
            if (query.from() != null) {
                time = time.gte(query.from());
            }
            if (query.to() != null) {
                time = time.lt(query.to());
            }
            parts.add(time);
        }
        if (!parts.isEmpty()) {
            criteria.andOperator(parts);
        }
        return new Query(criteria);
    }

    private static void equal(List<Criteria> parts, String field, String value) {
        if (hasText(value)) {
            parts.add(Criteria.where(field).is(value.strip()));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
