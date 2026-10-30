package com.catacumbas69.passario.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A bridge definition: one source topic whose messages are copied to every destination topic.
 *
 * <p>Schema evolution (MONGO-2): documents written before fan-out support have a single
 * {@code destination}; they are read through {@link #resolveDestinationTopics()} and rewritten to
 * {@code destinations} by the schema migration. Documents written before ordering modes have no
 * {@code ordered} flag; see {@link #isOrderedEffective()}.
 */
@Getter
@Setter
@Document(collection = "bridges")
public class BridgeDocument {

    private static final String SHARED = "Shared";

    @Id
    private String id;

    /** Unique, human-readable name. Also used in thread names and metric tags. */
    private String name;

    private String description;

    /** Whether the bridge should be running. */
    private boolean enabled = true;

    private BridgeSource source;

    /** One or more destination topics; every message is sent to all of them. */
    private List<BridgeDestination> destinations = new ArrayList<>();

    /** Legacy single destination. Only read when {@link #destinations} is empty. */
    @Deprecated
    private BridgeDestination destination;

    /** Copy the message key to the destinations. */
    private boolean preserveKey = true;

    /** Copy user-defined message properties to the destinations. */
    private boolean preserveProperties = true;

    /** Keep source order (PERF-4). {@code null} means "derive from the subscription type". */
    private Boolean ordered;

    /** Maximum unacknowledged messages for this bridge. {@code null} means the service default. */
    private Integer maxInFlight;

    /** Optimistic-locking version (RES-9); also used to detect configuration changes. */
    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Returns the destination topics, falling back to the legacy single destination, without blanks or
     * duplicates and in their original order.
     *
     * @return the effective destination topics, never {@code null}
     */
    public List<String> resolveDestinationTopics() {
        List<BridgeDestination> list = destinations != null && !destinations.isEmpty()
                ? destinations
                : legacyDestinationAsList();
        return list.stream()
                .filter(Objects::nonNull)
                .map(BridgeDestination::getTopic)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(topic -> !topic.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Whether this bridge keeps source order. When the flag was never set, a {@code Shared}
     * subscription means unordered (it cannot guarantee order) and any other type means ordered.
     *
     * @return {@code true} if the bridge runs in ordered mode
     */
    public boolean isOrderedEffective() {
        if (ordered != null) {
            return ordered;
        }
        return source == null || !SHARED.equals(source.subscriptionTypeOrDefault());
    }

    /**
     * Copies the user-editable definition from another document, leaving identity, version and
     * timestamps untouched.
     *
     * @param other the new definition
     */
    public void copyDefinitionFrom(BridgeDocument other) {
        this.name = other.name;
        this.description = other.description;
        this.enabled = other.enabled;
        this.source = other.source;
        this.destinations = new ArrayList<>(other.destinations);
        this.destination = null;
        this.preserveKey = other.preserveKey;
        this.preserveProperties = other.preserveProperties;
        this.ordered = other.ordered;
        this.maxInFlight = other.maxInFlight;
    }

    private List<BridgeDestination> legacyDestinationAsList() {
        return destination == null ? List.of() : List.of(destination);
    }
}
