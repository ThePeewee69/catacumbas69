package com.catacumbas69.catacommons;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration under {@code catacommons.*}. Only {@code catacommons.app} must be set; everything else
 * has a default.
 *
 * @param app this app's name, e.g. {@code catapulsar}: its namespace is {@code catacumbas69/<app>} and its
 *            events have source {@code /catacumbas69/<app>}
 * @param events event publishing
 * @param provisioning start-up creation of the tenant, namespace and topics
 * @param auditBridge start-up registration of the app's audit bridge in Cataponte (ADR 0009)
 * @param settings reader of the shared settings topic (ADR 0011)
 * @param consumers control of the app's own consumers (ADR 0015)
 */
@Validated
@ConfigurationProperties(prefix = "catacommons")
public record CatacommonsProperties(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]*") String app,
        @Valid @NotNull @DefaultValue Events events,
        @Valid @NotNull @DefaultValue Provisioning provisioning,
        @Valid @NotNull @DefaultValue AuditBridge auditBridge,
        @Valid @NotNull @DefaultValue Settings settings,
        @Valid @NotNull @DefaultValue Consumers consumers) {

    /**
     * Event publishing (ADR 0008).
     *
     * @param enabled {@code false} turns publishing off (events are dropped silently), e.g. in tests
     * @param retryDelays wait before each retry; three retries by default
     * @param sendTimeout how long one attempt may take
     */
    public record Events(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue({"1s", "2s", "4s"}) List<Duration> retryDelays,
            @NotNull @DefaultValue("10s") Duration sendTimeout) {
    }

    /**
     * Start-up provisioning (ADR 0008). Runs in the background and is retried until it succeeds.
     *
     * @param enabled {@code false} skips it (the namespace must then exist)
     * @param retryInterval first wait after a failed attempt; doubled after each failure
     * @param maxRetryInterval upper bound for the wait
     * @param eventsRetentionTime how long event messages are kept, consumed or not
     * @param eventsRetentionSize retention size limit of each topic in the namespace; must be larger than
     *                            {@code eventsBacklogQuota}
     * @param eventsBacklogQuota unconsumed backlog allowed per topic; above it publishing fails
     *                           ({@code producer_exception})
     * @param compactionThreshold backlog size that triggers automatic compaction of compacted topics
     */
    public record Provisioning(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("30s") Duration retryInterval,
            @NotNull @DefaultValue("5m") Duration maxRetryInterval,
            @NotNull @DefaultValue("7d") Duration eventsRetentionTime,
            @NotNull @DefaultValue("2GB") DataSize eventsRetentionSize,
            @NotNull @DefaultValue("1GB") DataSize eventsBacklogQuota,
            @NotNull @DefaultValue("1MB") DataSize compactionThreshold) {

        /**
         * Tells whether Pulsar accepts the backlog quota: it refuses ({@code 412}) a quota equal to or above the
         * namespace retention size, compared in whole megabytes as the retention is set. A retention size of zero
         * or less (none, or infinite) is not checked; missing values are reported by {@code @NotNull}.
         *
         * @return {@code true} when the backlog quota is below the retention size
         */
        @AssertTrue(message = "events-backlog-quota must be smaller than events-retention-size; "
                + "Pulsar refuses the backlog quota otherwise")
        public boolean isBacklogQuotaBelowRetention() {
            if (eventsRetentionSize == null || eventsBacklogQuota == null) {
                return true;
            }
            long retentionMegabytes = eventsRetentionSize.toMegabytes();
            return retentionMegabytes <= 0
                    || eventsBacklogQuota.toBytes() < DataSize.ofMegabytes(retentionMegabytes).toBytes();
        }
    }

    /**
     * Registration of the {@code audit-<app>} bridge (ADR 0009). Runs in the background and is retried until
     * Cataponte accepts it.
     *
     * @param enabled {@code false} skips it (Cataudit itself has nothing to audit)
     * @param cataponteUrl Cataponte's base URL ({@code CATAPONTE_URL}); Catapulsar uses its own setting instead
     * @param timeout connect and read timeout of one attempt
     * @param retryInterval first wait after a failed attempt; doubled after each failure
     * @param maxRetryInterval upper bound for the wait
     */
    public record AuditBridge(
            @DefaultValue("true") boolean enabled,
            @NotBlank @DefaultValue("http://cataponte:8082") String cataponteUrl,
            @NotNull @DefaultValue("5s") Duration timeout,
            @NotNull @DefaultValue("10s") Duration retryInterval,
            @NotNull @DefaultValue("5m") Duration maxRetryInterval) {
    }

    /**
     * Reader of the shared settings topic (ADR 0011).
     *
     * @param enabled {@code false} for an app that uses no shared setting (no reader is opened)
     * @param retryInterval wait before trying to open the reader again while Pulsar is down
     */
    public record Settings(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("10s") Duration retryInterval) {
    }

    /**
     * Control of the app's own consumers (ADR 0015, orch.md decisions 66–68).
     *
     * @param enabled {@code true} in apps with consumers to control; declares the status and commands topics
     * @param statusInterval how often each replica publishes its consumers' status
     * @param resumeCheckInterval how often a consumer paused automatically checks whether it can resume
     * @param maxDeliveries deliveries before a message goes to the DLQ, unless the shared setting
     *                      {@code dlq.max-deliveries} says otherwise
     */
    public record Consumers(
            @DefaultValue("false") boolean enabled,
            @NotNull @DefaultValue("10s") Duration statusInterval,
            @NotNull @DefaultValue("5s") Duration resumeCheckInterval,
            @Min(2) @Max(100) @DefaultValue("10") int maxDeliveries) {
    }
}
