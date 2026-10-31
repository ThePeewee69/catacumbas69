package com.catacumbas69.catapulsar.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/topics/{tenant}/{namespace}/{topic}/produce}.
 *
 * @param payload message text (UTF-8), at most 1 MiB
 * @param key optional message key
 * @param properties optional user-defined properties
 */
public record ProduceRequest(
        @NotNull @Size(max = ProduceRequest.MAX_PAYLOAD_CHARS) String payload,
        @Size(max = 256) String key,
        @Size(max = 64) Map<@Size(max = 256) String, @Size(max = 4096) String> properties) {

    /** Largest payload accepted, in characters (SEC-4). */
    public static final int MAX_PAYLOAD_CHARS = 1_048_576;
}
