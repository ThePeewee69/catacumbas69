package com.catacumbas69.cataponte;

import com.catacumbas69.cataponte.config.CataponteProperties;
import java.time.Duration;
import java.util.List;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CataponteProperties properties() {
        return properties(1000);
    }

    public static CataponteProperties properties(int defaultMaxInFlight) {
        return new CataponteProperties(
                new CataponteProperties.Pulsar("pulsar://localhost:6650", "", Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(100)),
                bridge(defaultMaxInFlight),
                new CataponteProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new CataponteProperties.Cors(List.of()));
    }

    public static CataponteProperties.Bridge bridge(int defaultMaxInFlight) {
        return new CataponteProperties.Bridge(
                Duration.ofSeconds(5),
                defaultMaxInFlight,
                Duration.ofSeconds(1),
                Duration.ofMillis(1),
                100,
                100,
                Duration.ofMillis(10),
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(2));
    }
}
