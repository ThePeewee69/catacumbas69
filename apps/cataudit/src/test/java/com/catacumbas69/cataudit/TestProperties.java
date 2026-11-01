package com.catacumbas69.cataudit;

import com.catacumbas69.cataudit.config.CatauditProperties;
import java.time.Duration;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CatauditProperties properties() {
        return new CatauditProperties(
                new CatauditProperties.Pulsar("http://localhost:8080", "pulsar://localhost:6650", "",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new CatauditProperties.Consumer("cataudit", 10, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new CatauditProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new CatauditProperties.Api(50, 500));
    }
}
