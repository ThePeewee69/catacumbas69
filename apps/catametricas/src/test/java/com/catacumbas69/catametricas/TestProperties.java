package com.catacumbas69.catametricas;

import com.catacumbas69.catametricas.config.CatametricasProperties;
import java.time.Duration;

/**
 * Small settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CatametricasProperties properties() {
        return new CatametricasProperties(
                new CatametricasProperties.Pulsar("http://localhost:8080", Duration.ofSeconds(1), Duration.ofSeconds(1),
                        Duration.ofSeconds(1)),
                new CatametricasProperties.Collect(60, Duration.ofSeconds(10), 4, Duration.ofDays(30), true),
                new CatametricasProperties.Mongo(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
