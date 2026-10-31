package com.catacumbas69.pulsario;

import com.catacumbas69.pulsario.config.PulsarioProperties;
import java.time.Duration;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static PulsarioProperties properties() {
        return properties(10, 100);
    }

    public static PulsarioProperties properties(int maxCachedProducers, int maxPeekCount) {
        return new PulsarioProperties(
                new PulsarioProperties.Pulsar("http://localhost:8080", "pulsar://localhost:6650", "",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofMinutes(5), maxCachedProducers, maxPeekCount),
                new PulsarioProperties.Passario("http://localhost:8082", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new PulsarioProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
