package com.catacumbas69.catametricas.model;

import java.time.Instant;

/**
 * One collection task on {@code catacumbas69/catametricas/collect-tasks} (ADR 0012): "read the statistics of
 * this namespace for this interval". Every instance publishes one per namespace and interval, so the same task
 * arrives several times; the first instance to claim its id does it.
 *
 * <p>Times are ISO-8601 text so the JSON schema registered in Pulsar stays plain.
 *
 * @param id {@code <tenant>/<namespace>@<bucket>}, e.g. {@code home/media@2026-11-01T10:15:00Z}
 * @param namespace full namespace, e.g. {@code home/media}
 * @param bucket start of the interval the task belongs to (UTC, ISO-8601)
 * @param intervalSeconds interval in use when the task was published
 */
public record CollectTask(String id, String namespace, String bucket, int intervalSeconds) {

    /**
     * Creates the task of a namespace and interval.
     *
     * @param namespace full namespace, {@code tenant/namespace}
     * @param bucket start of the interval
     * @param intervalSeconds interval length
     * @return the task
     */
    public static CollectTask of(String namespace, Instant bucket, int intervalSeconds) {
        return new CollectTask(namespace + "@" + bucket, namespace, bucket.toString(), intervalSeconds);
    }

    /**
     * Tells whether the task has what an instance needs to run it.
     *
     * @return {@code true} if id and namespace ({@code tenant/namespace}) are present
     */
    public boolean complete() {
        return id != null && !id.isBlank() && namespace != null && namespace.indexOf('/') > 0
                && namespace.indexOf('/') == namespace.lastIndexOf('/') && !namespace.endsWith("/");
    }
}
