package com.catacumbas69.catapulsar;

import com.catacumbas69.catapulsar.config.CatapulsarProperties;
import java.time.Duration;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CatapulsarProperties properties() {
        return properties(10, 100);
    }

    public static CatapulsarProperties properties(int maxCachedProducers, int maxPeekCount) {
        return new CatapulsarProperties(
                new CatapulsarProperties.Pulsar("http://localhost:8080", "pulsar://localhost:6650", "",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofMinutes(5), maxCachedProducers, maxPeekCount),
                new CatapulsarProperties.Cataponte("http://localhost:8082", Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new CatapulsarProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new CatapulsarProperties.Ui(10),
                new CatapulsarProperties.Catametricas(60));
    }
}
