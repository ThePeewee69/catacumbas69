package com.catacumbas69.catapulsar.service;

import static com.catacumbas69.catapulsar.model.CatametricasSchema.BACKLOG_MSGS;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BASE_NAME;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BYTES_IN;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.BYTES_OUT;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MSGS_IN;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.MSGS_OUT;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.NAMESPACE;
import static com.catacumbas69.catapulsar.model.CatametricasSchema.TENANT;

import com.catacumbas69.catapulsar.dto.OverviewResponse.Current;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Row;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Series;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Traffic;
import com.catacumbas69.catapulsar.model.CatametricasSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;

/**
 * Builds overview rows from Catametricas documents: sums topics into the row they belong to (tenant,
 * namespace or topic), places history into chart buckets and adds everything up for the summary. Pure
 * logic, no I/O, so it is unit-tested on its own.
 */
public final class OverviewAssembler {

    private final String keyField;
    private final List<Instant> buckets;
    private final long stepMillis;
    private final Map<String, RowBuilder> rows = new TreeMap<>();

    /**
     * Creates an assembler.
     *
     * @param keyField field that names a row: {@code tenant}, {@code namespace} or {@code baseName}
     * @param buckets chart bucket starts, oldest first
     * @param step bucket length
     */
    public OverviewAssembler(String keyField, List<Instant> buckets, Duration step) {
        this.keyField = keyField;
        this.buckets = buckets;
        this.stepMillis = step.toMillis();
    }

    /**
     * Adds the latest snapshot of an existing topic.
     *
     * @param snapshot document from {@code contario_topic_snapshots}
     * @return this assembler
     */
    public OverviewAssembler addSnapshot(Document snapshot) {
        row(snapshot.getString(keyField)).addSnapshot(snapshot);
        return this;
    }

    /**
     * Adds the all-time totals of a topic.
     *
     * @param total document from {@code contario_topic_totals}
     * @return this assembler
     */
    public OverviewAssembler addTotal(Document total) {
        RowBuilder row = row(total.getString(keyField));
        row.total = row.total.plus(trafficOf(total));
        return this;
    }

    /**
     * Adds history already summed per row and time by MongoDB.
     *
     * @param history document with {@code _id.<keyField>}, {@code _id.<timeField>}, traffic and backlog
     * @param timeField {@code minute} or {@code hour}
     * @return this assembler
     */
    public OverviewAssembler addHistory(Document history, String timeField) {
        Document id = history.get("_id", Document.class);
        Instant time = instant(id.get(timeField));
        int bucket = bucketOf(time);
        if (bucket < 0) {
            return this;
        }
        RowBuilder row = row(id.getString(keyField));
        Traffic traffic = trafficOf(history);
        row.period = row.period.plus(traffic);
        row.msgsIn[bucket] += traffic.msgsIn();
        row.msgsOut[bucket] += traffic.msgsOut();
        if (row.backlogAt[bucket] == null || !time.isBefore(row.backlogAt[bucket])) {
            row.backlogAt[bucket] = time;
            row.backlog[bucket] = number(history, BACKLOG_MSGS);
        }
        return this;
    }

    /**
     * Returns the rows, sorted by name.
     *
     * @return one row per tenant, namespace or topic
     */
    public List<Row> rows() {
        return rows.entrySet().stream().map(entry -> entry.getValue().build(entry.getKey())).toList();
    }

    /**
     * Returns the sum of all rows.
     *
     * @return the summary row (name {@code null})
     */
    public Row summary() {
        RowBuilder sum = new RowBuilder(buckets.size());
        for (RowBuilder row : rows.values()) {
            sum.topicKeys.addAll(row.topicKeys);
            if (row.current != null) {
                sum.current = sum.current == null ? row.current.clone() : add(sum.current, row.current);
            }
            sum.partitions += row.partitions;
            sum.total = sum.total.plus(row.total);
            sum.period = sum.period.plus(row.period);
            for (int i = 0; i < buckets.size(); i++) {
                sum.msgsIn[i] += row.msgsIn[i];
                sum.msgsOut[i] += row.msgsOut[i];
                if (row.backlog[i] != null) {
                    sum.backlog[i] = (sum.backlog[i] == null ? 0 : sum.backlog[i]) + row.backlog[i];
                }
            }
        }
        return sum.build(null);
    }

    private RowBuilder row(String name) {
        return rows.computeIfAbsent(name == null ? "?" : name, key -> new RowBuilder(buckets.size()));
    }

    private int bucketOf(Instant time) {
        if (time == null || buckets.isEmpty()) {
            return -1;
        }
        long offset = time.toEpochMilli() - buckets.get(0).toEpochMilli();
        int index = (int) Math.floorDiv(offset, stepMillis);
        return index >= 0 && index < buckets.size() ? index : -1;
    }

    private static double[] add(double[] into, double[] values) {
        for (int i = 0; i < into.length; i++) {
            into[i] += values[i];
        }
        return into;
    }

    private static Traffic trafficOf(Document document) {
        return new Traffic(number(document, MSGS_IN), number(document, MSGS_OUT), number(document, BYTES_IN),
                number(document, BYTES_OUT));
    }

    static long number(Document document, String field) {
        Object value = document.get(field);
        return value instanceof Number number ? number.longValue() : 0;
    }

    static double decimal(Document document, String field) {
        Object value = document.get(field);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return value instanceof Instant instant ? instant : null;
    }

    /** Running sums of one row. Current values are kept in an array in {@link CurrentField} order. */
    private static final class RowBuilder {
        private double[] current;
        private final Set<String> topicKeys = new HashSet<>();
        private int partitions;
        private Traffic total = Traffic.NONE;
        private Traffic period = Traffic.NONE;
        private final long[] msgsIn;
        private final long[] msgsOut;
        private final Long[] backlog;
        private final Instant[] backlogAt;

        RowBuilder(int buckets) {
            msgsIn = new long[buckets];
            msgsOut = new long[buckets];
            backlog = new Long[buckets];
            backlogAt = new Instant[buckets];
        }

        void addSnapshot(Document snapshot) {
            double[] values = new double[CurrentField.values().length];
            for (CurrentField field : CurrentField.values()) {
                values[field.ordinal()] = decimal(snapshot, field.field);
            }
            current = current == null ? values : add(current, values);
            topicKeys.add(snapshot.getString(TENANT) + "/" + snapshot.getString(NAMESPACE) + "/"
                    + snapshot.getString(BASE_NAME));
            partitions++;
        }

        Row build(String name) {
            Current state = current == null ? null : new Current(topicKeys.size(), partitions,
                    (long) get(CurrentField.BACKLOG_MSGS), (long) get(CurrentField.BACKLOG_BYTES),
                    get(CurrentField.RATE_IN), get(CurrentField.RATE_OUT),
                    get(CurrentField.THROUGHPUT_IN), get(CurrentField.THROUGHPUT_OUT),
                    (long) get(CurrentField.STORAGE), (int) get(CurrentField.SUBSCRIPTIONS),
                    (int) get(CurrentField.PRODUCERS), (int) get(CurrentField.CONSUMERS));
            Series series = new Series(Arrays.stream(msgsIn).boxed().toList(), Arrays.stream(msgsOut).boxed().toList(),
                    new ArrayList<>(Arrays.asList(backlog)));
            return new Row(name, state, period, total, series);
        }

        private double get(CurrentField field) {
            return current[field.ordinal()];
        }
    }

    /** Snapshot fields summed into the current state. */
    private enum CurrentField {
        BACKLOG_MSGS(CatametricasSchema.BACKLOG_MSGS),
        BACKLOG_BYTES(CatametricasSchema.BACKLOG_BYTES),
        RATE_IN("msgRateIn"),
        RATE_OUT("msgRateOut"),
        THROUGHPUT_IN("throughputIn"),
        THROUGHPUT_OUT("throughputOut"),
        STORAGE("storageSize"),
        SUBSCRIPTIONS("subscriptions"),
        PRODUCERS("producers"),
        CONSUMERS("consumers");

        private final String field;

        CurrentField(String field) {
            this.field = field;
        }
    }
}
