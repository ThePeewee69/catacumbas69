package com.catacumbas69.catapulsar.service;

/**
 * A non-partitioned topic cannot be created because a partitioned topic with the same name exists. Until its
 * partitions are created (by a producer, a consumer or {@code create-missed-partitions}) it has no ledger, so it is
 * not in the topic list ({@code getList}).
 */
public class PartitionedTopicExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param topic fully-qualified topic
     * @param partitions its number of partitions
     */
    public PartitionedTopicExistsException(String topic, int partitions) {
        super(topic + " already exists as a partitioned topic with " + partitions + " partition(s); it is not listed"
                + " until its partitions are created. Delete it (pulsar-admin topics delete-partitioned-topic -f "
                + topic + ") or create its partitions (pulsar-admin topics create-missed-partitions " + topic + ").");
    }
}
