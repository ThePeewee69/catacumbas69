package com.catacumbas69.cataponte.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An area: a named group of bridges whose topics must match its glob patterns (ADR 0014). A bridge joins an area
 * through {@link BridgeDocument#getAreaId()}.
 */
@Getter
@Setter
@Document(collection = AreaDocument.COLLECTION)
public class AreaDocument {

    /** Cataponte's areas. */
    public static final String COLLECTION = "cataponte_areas";

    @Id
    private String id;

    /** Unique, human-readable name. */
    private String name;

    private String description;

    /** Glob patterns over topic names; empty means every topic (see {@code AreaFilter}). */
    private List<String> patterns = new ArrayList<>();

    /** Optimistic-locking version (RES-9). */
    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
