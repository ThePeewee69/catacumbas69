package com.catacumbas69.catavigia.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A subscription's own backlog limit, stored in {@value Schema#BACKLOG_LIMITS} (orch.md decision 70). Without one,
 * the global default ({@code alerts.backlog-limit}) applies.
 *
 * @param id {@code <topic>|<subscription>} ({@code _id})
 * @param topic topic, without partition suffix, e.g. {@code persistent://home/media/orders}
 * @param subscription subscription name
 * @param limit messages above which {@code backlog} opens
 * @param updatedBy who set it
 * @param updatedAt when
 */
@Document(collection = Schema.BACKLOG_LIMITS)
public record BacklogLimit(
        @Id String id,
        String topic,
        String subscription,
        long limit,
        String updatedBy,
        Instant updatedAt) {

    /**
     * Returns the id of a topic and subscription.
     *
     * @param topic topic without partition suffix
     * @param subscription subscription name
     * @return {@code <topic>|<subscription>}
     */
    public static String idOf(String topic, String subscription) {
        return topic + "|" + subscription;
    }
}
