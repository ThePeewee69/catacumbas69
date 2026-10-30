package com.catacumbas69.passario;

import com.catacumbas69.passario.config.PassarioProperties;
import java.time.Duration;
import java.util.List;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static PassarioProperties properties() {
        return properties(1000);
    }

    public static PassarioProperties properties(int defaultMaxInFlight) {
        return new PassarioProperties(
                new PassarioProperties.Pulsar("pulsar://localhost:6650", "", Duration.ofSeconds(1),
                        Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(100)),
                bridge(defaultMaxInFlight),
                new PassarioProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new PassarioProperties.Cors(List.of()));
    }

    public static PassarioProperties.Bridge bridge(int defaultMaxInFlight) {
        return new PassarioProperties.Bridge(
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
