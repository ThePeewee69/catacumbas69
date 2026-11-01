package com.catacumbas69.catametricas.service;

import com.catacumbas69.catametricas.model.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A validated series request: scope, grouping and buckets.
 *
 * @param tenant tenant to stay in, or {@code null} for the whole cluster
 * @param namespace namespace (without tenant) to stay in, or {@code null}; needs {@code tenant}
 * @param groupBy what one group is
 * @param times start of every bucket, oldest first
 * @param step bucket length
 */
public record SeriesQuery(String tenant, String namespace, GroupBy groupBy, List<Instant> times, Duration step) {

    /** Most buckets one request may ask for. */
    public static final int MAX_BUCKETS = 2_000;

    /** What one group of a series is. */
    public enum GroupBy {
        /** One group per tenant. */
        TENANT(Schema.TENANT),
        /** One group per namespace of a tenant. */
        NAMESPACE(Schema.NAMESPACE),
        /** One group per topic of a namespace, partitions summed. */
        TOPIC(Schema.BASE_NAME);

        private final String field;

        GroupBy(String field) {
            this.field = field;
        }

        /**
         * Returns the MongoDB field that names a group.
         *
         * @return {@code tenant}, {@code namespace} or {@code baseName}
         */
        public String field() {
            return field;
        }

        /**
         * Returns the request value.
         *
         * @return {@code tenant}, {@code namespace} or {@code topic}
         */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Validates a request.
     *
     * @param tenant tenant, or {@code null}
     * @param namespace namespace, or {@code null}
     * @param groupBy {@code tenant}, {@code namespace} or {@code topic}
     * @param from start of the range (rounded down to a bucket start)
     * @param to end of the range (its bucket is the last one)
     * @param step bucket length: whole minutes below one hour ({@code 15m}), whole hours from one hour ({@code 6h})
     * @return the query
     * @throws InvalidQueryException if something does not fit
     */
    public static SeriesQuery of(String tenant, String namespace, String groupBy, Instant from, Instant to,
                                 String step) {
        if (namespace != null && tenant == null) {
            throw new InvalidQueryException("namespace needs tenant");
        }
        GroupBy group = parseGroupBy(groupBy);
        if (group == GroupBy.NAMESPACE && tenant == null) {
            throw new InvalidQueryException("groupBy=namespace needs tenant");
        }
        if (group == GroupBy.TOPIC && namespace == null) {
            throw new InvalidQueryException("groupBy=topic needs tenant and namespace");
        }
        Duration length = parseStep(step);
        if (from == null || to == null || to.isBefore(from)) {
            throw new InvalidQueryException("from must be before to");
        }
        long stepMillis = length.toMillis();
        long first = Math.floorDiv(from.toEpochMilli(), stepMillis) * stepMillis;
        long last = Math.floorDiv(to.toEpochMilli(), stepMillis) * stepMillis;
        long count = (last - first) / stepMillis + 1;
        if (count > MAX_BUCKETS) {
            throw new InvalidQueryException("range has " + count + " buckets, at most " + MAX_BUCKETS + " allowed");
        }
        List<Instant> times = new ArrayList<>((int) count);
        for (long time = first; time <= last; time += stepMillis) {
            times.add(Instant.ofEpochMilli(time));
        }
        return new SeriesQuery(tenant, namespace, group, List.copyOf(times), length);
    }

    /**
     * Tells whether the hourly history is read (steps of one hour or more) instead of the per-minute one.
     *
     * @return {@code true} for hours
     */
    public boolean hourly() {
        return step.compareTo(Duration.ofHours(1)) >= 0;
    }

    /**
     * Returns the history collection read.
     *
     * @return {@value Schema#HOURS} or {@value Schema#MINUTES}
     */
    public String collection() {
        return hourly() ? Schema.HOURS : Schema.MINUTES;
    }

    /**
     * Returns the time field of that collection.
     *
     * @return {@value Schema#HOUR} or {@value Schema#MINUTE}
     */
    public String timeField() {
        return hourly() ? Schema.HOUR : Schema.MINUTE;
    }

    /**
     * Returns the end of the range (exclusive).
     *
     * @return the end of the last bucket
     */
    public Instant end() {
        return times.get(times.size() - 1).plus(step);
    }

    private static GroupBy parseGroupBy(String value) {
        for (GroupBy group : GroupBy.values()) {
            if (group.value().equals(value)) {
                return group;
            }
        }
        throw new InvalidQueryException("groupBy must be tenant, namespace or topic");
    }

    private static Duration parseStep(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}[mh]")) {
            throw new InvalidQueryException("step must be minutes or hours, e.g. 15m or 6h");
        }
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        Duration step = value.endsWith("h") ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        if (step.compareTo(Duration.ofHours(1)) >= 0 && step.toMinutes() % 60 != 0) {
            throw new InvalidQueryException("steps of an hour or more must be whole hours, e.g. 1h or 6h");
        }
        return step;
    }
}
