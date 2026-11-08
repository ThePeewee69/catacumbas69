package com.catacumbas69.catavigia.service;

import static com.catacumbas69.catavigia.model.Schema.CLOSED_AT;
import static com.catacumbas69.catavigia.model.Schema.SEEN_AT;
import static com.catacumbas69.catavigia.model.Schema.SEEN_BY;
import static com.catacumbas69.catavigia.model.Schema.SUBJECT;
import static com.catacumbas69.catavigia.model.Schema.TYPE;

import com.catacumbas69.catavigia.config.CatavigiaProperties;
import com.catacumbas69.catavigia.dto.AlertPageResponse;
import com.catacumbas69.catavigia.dto.AlertResponse;
import com.catacumbas69.catavigia.dto.AlertSummaryResponse;
import com.catacumbas69.catavigia.model.Alert;
import com.catacumbas69.catavigia.model.AlertTypes;
import com.catacumbas69.catavigia.model.ArchivedAlert;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads the alerts for the API, and marks them as seen (orch.md decision 71). The open alerts include this
 * replica's {@code mongodb.down}, so they are answered even while MongoDB is down.
 */
@Service
public class AlertQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(AlertQueryService.class);
    private static final Comparator<AlertResponse> OPEN_ORDER = Comparator
            .comparing((AlertResponse alert) -> AlertTypes.CRITICAL.equals(alert.severity()) ? 0 : 1)
            .thenComparing(AlertResponse::openedAt, Comparator.reverseOrder());

    private final MongoTemplate mongoTemplate;
    private final MongoWatch mongoWatch;
    private final AlertEvents events;
    private final CatavigiaProperties.Api api;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param mongoTemplate template for the alerts
     * @param mongoWatch this replica's {@code mongodb.down}
     * @param events publishes {@code alert.seen}
     * @param properties application properties, for the page sizes
     * @param clock time source
     */
    public AlertQueryService(MongoTemplate mongoTemplate, MongoWatch mongoWatch, AlertEvents events,
                             CatavigiaProperties properties, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.mongoWatch = mongoWatch;
        this.events = events;
        this.api = properties.api();
        this.clock = clock;
    }

    /**
     * Returns the open alerts, critical first, newest first.
     *
     * @return the alerts; only {@code mongodb.down} while MongoDB is down
     */
    public List<AlertResponse> open() {
        List<AlertResponse> open = new ArrayList<>();
        stored().ifPresent(alerts -> alerts.forEach(alert -> open.add(AlertResponse.open(alert, false))));
        mongoWatch.alert().ifPresent(alert -> open.add(AlertResponse.open(alert, true)));
        open.sort(OPEN_ORDER);
        return open;
    }

    /**
     * Counts the open alerts not marked as seen.
     *
     * @return the summary
     */
    public AlertSummaryResponse summary() {
        Optional<List<Alert>> stored = stored();
        List<Alert> all = new ArrayList<>(stored.orElse(List.of()));
        mongoWatch.alert().ifPresent(all::add);
        int critical = 0;
        int warning = 0;
        for (Alert alert : all) {
            if (alert.seen()) {
                continue;
            }
            if (AlertTypes.CRITICAL.equals(alert.severity())) {
                critical++;
            } else {
                warning++;
            }
        }
        String worst = critical > 0 ? AlertTypes.CRITICAL : warning > 0 ? AlertTypes.WARNING : null;
        return new AlertSummaryResponse(all.size(), critical + warning, critical, warning, worst, stored.isPresent());
    }

    /**
     * Returns one page of the archive, newest closed first.
     *
     * @param type only this type, or {@code null}
     * @param subject only subjects starting with this, or {@code null}
     * @param from only alerts closed at or after this, or {@code null}
     * @param to only alerts closed before this, or {@code null}
     * @param page page number, from 0
     * @param size page size, or {@code null} for the default
     * @return the page
     */
    public AlertPageResponse archive(String type, String subject, Instant from, Instant to, int page, Integer size) {
        int pageSize = size == null ? api.defaultPageSize() : Math.min(Math.max(1, size), api.maxPageSize());
        Criteria criteria = new Criteria();
        if (type != null && !type.isBlank()) {
            criteria = criteria.and(TYPE).is(type);
        }
        if (subject != null && !subject.isBlank()) {
            criteria = criteria.and(SUBJECT).regex("^" + Pattern.quote(subject));
        }
        if (from != null || to != null) {
            criteria = criteria.and(CLOSED_AT);
            if (from != null) {
                criteria = criteria.gte(from);
            }
            if (to != null) {
                criteria = criteria.lt(to);
            }
        }
        Query query = Query.query(criteria);
        long total = mongoTemplate.count(query, ArchivedAlert.class);
        query.with(Sort.by(Sort.Direction.DESC, CLOSED_AT)).skip((long) Math.max(0, page) * pageSize)
                .limit(pageSize);
        List<AlertResponse> items = mongoTemplate.find(query, ArchivedAlert.class).stream()
                .map(AlertResponse::archived).toList();
        return new AlertPageResponse(items, total, Math.max(0, page), pageSize);
    }

    /**
     * Returns one alert, open or archived.
     *
     * @param id the alert's id
     * @return the alert
     * @throws ResponseStatusException {@code 404} if unknown
     */
    public AlertResponse get(String id) {
        Optional<Alert> local = mongoWatch.alert().filter(alert -> alert.id().equals(id));
        if (local.isPresent()) {
            return AlertResponse.open(local.get(), true);
        }
        Alert open = mongoTemplate.findById(id, Alert.class);
        if (open != null) {
            return AlertResponse.open(open, false);
        }
        ArchivedAlert archived = mongoTemplate.findById(id, ArchivedAlert.class);
        if (archived != null) {
            return AlertResponse.archived(archived);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No alert " + id);
    }

    /**
     * Marks an open alert as seen: it stays open but leaves the badge count. Marking it again changes nothing.
     *
     * @param id the alert's id
     * @param who client address
     * @return the alert
     * @throws ResponseStatusException {@code 404} if no open alert has this id, {@code 409} for a
     *         {@code mongodb.down} kept in memory
     */
    public AlertResponse markSeen(String id, String who) {
        if (mongoWatch.alert().filter(alert -> alert.id().equals(id)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "mongodb.down is kept in memory while MongoDB is down and cannot be marked as seen");
        }
        Alert marked = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(id).and(SEEN_AT).isNull()),
                new Update().set(SEEN_BY, who).set(SEEN_AT, clock.instant()),
                FindAndModifyOptions.options().returnNew(true), Alert.class);
        if (marked != null) {
            events.seen(marked);
            return AlertResponse.open(marked, false);
        }
        Alert open = mongoTemplate.findById(id, Alert.class);
        if (open == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No open alert " + id);
        }
        return AlertResponse.open(open, false);
    }

    private Optional<List<Alert>> stored() {
        try {
            return Optional.of(mongoTemplate.findAll(Alert.class));
        } catch (DataAccessException e) {
            LOG.debug("Open alerts not read, MongoDB unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
