package com.catacumbas69.passario.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Data
@Document(collection = "bridges")
public class BridgeDocument {

    @Id
    private String id;

    /** Human-readable name for this bridge */
    private String name;

    /** Optional description */
    private String description;

    /** Whether this bridge should be actively running */
    private boolean enabled = true;

    private BridgeSource source;

    /** One or more destination topics — every message is fanned out to all of them */
    private List<BridgeDestination> destinations = new ArrayList<>();

    /**
     * Legacy single destination (bridges created before fan-out support).
     * Only read when {@link #destinations} is empty; cleared on the next save.
     */
    @Deprecated
    private BridgeDestination destination;

    /** Forward the message key from source to destination */
    private boolean preserveKey = true;

    /** Forward user-defined message properties */
    private boolean preserveProperties = true;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** Effective destination topics: {@code destinations}, falling back to the legacy single field. */
    public List<String> resolveDestinationTopics() {
        List<BridgeDestination> list = (destinations != null && !destinations.isEmpty())
                ? destinations
                : (destination != null ? List.of(destination) : List.of());
        return list.stream()
                .map(BridgeDestination::getTopic)
                .filter(Objects::nonNull)
                .filter(t -> !t.isBlank())
                .distinct()
                .toList();
    }
}
