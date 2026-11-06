package com.catacumbas69.cataponte.service;

import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Message;
import org.springframework.stereotype.Component;

/**
 * Skips the message an ordered {@code Failover} bridge is stuck on (orch.md decision 69), through Pulsar's admin API,
 * so it works whichever replica holds the active consumer.
 *
 * <p>An ordered bridge acknowledges in order and stops at the message that keeps failing, so that message is the
 * first one not acknowledged on the subscription. It is skipped only if it still is (same ledger and entry), with
 * {@code skip 1}. A message sent in a batch is skipped with its whole batch.
 */
@Component
public class MessageSkipper {

    private final PulsarAdmin admin;

    /**
     * Creates the skipper.
     *
     * @param admin Pulsar admin client
     */
    public MessageSkipper(PulsarAdmin admin) {
        this.admin = admin;
    }

    /**
     * Skips the first unacknowledged message of the bridge's subscription if it is {@code messageId}.
     *
     * @param bridge the bridge
     * @param messageId the message the bridge reported it is stuck on
     * @throws BridgeValidationException if the source topic is partitioned
     * @throws BridgeConflictException if that message is no longer the first one
     * @throws PulsarAdminException if Pulsar refuses
     */
    public void skip(BridgeDocument bridge, String messageId) throws PulsarAdminException {
        String topic = bridge.getSource().getTopic();
        String subscription = bridge.getSource().getSubscription();
        if (admin.topics().getPartitionedTopicMetadata(topic).partitions > 0) {
            throw new BridgeValidationException("source.topic",
                    "Skip this message is not available for partitioned source topics");
        }
        List<Message<byte[]>> head = admin.topics().peekMessages(topic, subscription, 1);
        if (head.isEmpty() || !sameEntry(head.get(0).getMessageId().toString(), messageId)) {
            throw new BridgeConflictException("The bridge is no longer stuck on message " + messageId
                    + "; reload the bridges");
        }
        admin.topics().skipMessages(topic, subscription, 1);
    }

    /** Compares ledger and entry, ignoring partition and batch index. */
    static boolean sameEntry(String a, String b) {
        String[] left = a.split(":");
        String[] right = b.split(":");
        return left.length >= 2 && right.length >= 2 && left[0].equals(right[0]) && left[1].equals(right[1]);
    }
}
