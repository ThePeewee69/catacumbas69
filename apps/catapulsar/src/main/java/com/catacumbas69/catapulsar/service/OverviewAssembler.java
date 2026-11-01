package com.catacumbas69.catapulsar.service;

import com.catacumbas69.catapulsar.dto.OverviewResponse.Current;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Row;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Series;
import com.catacumbas69.catapulsar.dto.OverviewResponse.Traffic;
import com.catacumbas69.catapulsar.service.CatametricasClient.Group;
import com.catacumbas69.catapulsar.service.CatametricasClient.TopicStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds overview rows from Catametricas' answers: sums topics into the row they belong to (tenant, namespace or
 * topic), places the series into the chart buckets and adds everything up for the summary. Pure logic, no I/O,
 * so it is unit-tested on its own.
 */
public final class OverviewAssembler {

    private final OverviewScope scope;
    private final List<Instant> buckets;
    private final Map<Instant, Integer> bucketIndex = new HashMap<>();
    private final Map<String, RowBuilder> rows = new TreeMap<>();

    /**
     * Creates an assembler.
     *
     * @param scope what a row is
     * @param buckets chart bucket starts, oldest first
     */
    public OverviewAssembler(OverviewScope scope, List<Instant> buckets) {
        this.scope = scope;
        this.buckets = buckets;
        for (int i = 0; i < buckets.size(); i++) {
            bucketIndex.put(buckets.get(i), i);
        }
    }

    /**
     * Adds a topic (or partition): its current state if it still exists and its all-time traffic.
     *
     * @param topic statistics from Catametricas' {@code /api/v1/topics}
     * @return this assembler
     */
    public OverviewAssembler addTopic(TopicStats topic) {
        RowBuilder row = row(scope.rowOf(topic));
        if (topic.current() != null) {
            row.addCurrent(topic);
        }
        if (topic.total() != null) {
            row.total = row.total.plus(new Traffic(topic.total().msgsIn(), topic.total().msgsOut(),
                    topic.total().bytesIn(), topic.total().bytesOut()));
        }
        return this;
    }

    /**
     * Adds the series of one row; buckets that are not on the chart are ignored.
     *
     * @param times bucket starts of the series
     * @param group the row's series from Catametricas' {@code /api/v1/series}
     * @return this assembler
     */
    public OverviewAssembler addSeries(List<Instant> times, Group group) {
        RowBuilder row = row(group.key());
        for (int i = 0; i < times.size(); i++) {
            Integer bucket = bucketIndex.get(times.get(i));
            if (bucket == null) {
                continue;
            }
            Traffic traffic = new Traffic(value(group.msgsIn(), i), value(group.msgsOut(), i),
                    value(group.bytesIn(), i), value(group.bytesOut(), i));
            row.period = row.period.plus(traffic);
            row.msgsIn[bucket] += traffic.msgsIn();
            row.msgsOut[bucket] += traffic.msgsOut();
            Long backlog = i < group.backlog().size() ? group.backlog().get(i) : null;
            if (backlog != null) {
                row.backlog[bucket] = backlog;
            }
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

    private static long value(List<Long> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) {
            return 0;
        }
        return values.get(index);
    }

    private static double[] add(double[] into, double[] values) {
        for (int i = 0; i < into.length; i++) {
            into[i] += values[i];
        }
        return into;
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

        RowBuilder(int buckets) {
            msgsIn = new long[buckets];
            msgsOut = new long[buckets];
            backlog = new Long[buckets];
        }

        void addCurrent(TopicStats topic) {
            CatametricasClient.Current state = topic.current();
            double[] values = new double[CurrentField.values().length];
            values[CurrentField.BACKLOG_MSGS.ordinal()] = state.backlogMsgs();
            values[CurrentField.BACKLOG_BYTES.ordinal()] = state.backlogBytes();
            values[CurrentField.RATE_IN.ordinal()] = state.msgRateIn();
            values[CurrentField.RATE_OUT.ordinal()] = state.msgRateOut();
            values[CurrentField.THROUGHPUT_IN.ordinal()] = state.throughputIn();
            values[CurrentField.THROUGHPUT_OUT.ordinal()] = state.throughputOut();
            values[CurrentField.STORAGE.ordinal()] = state.storageSize();
            values[CurrentField.SUBSCRIPTIONS.ordinal()] = state.subscriptions();
            values[CurrentField.PRODUCERS.ordinal()] = state.producers();
            values[CurrentField.CONSUMERS.ordinal()] = state.consumers();
            current = current == null ? values : add(current, values);
            topicKeys.add(topic.tenant() + "/" + topic.namespace() + "/" + topic.baseName());
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

    /** Current values summed per row. */
    private enum CurrentField {
        BACKLOG_MSGS, BACKLOG_BYTES, RATE_IN, RATE_OUT, THROUGHPUT_IN, THROUGHPUT_OUT, STORAGE, SUBSCRIPTIONS,
        PRODUCERS, CONSUMERS
    }
}
