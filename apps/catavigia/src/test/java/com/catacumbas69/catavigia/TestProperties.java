package com.catacumbas69.catavigia;

import com.catacumbas69.catavigia.config.CatavigiaProperties;
import java.time.Duration;

/**
 * Small, fast settings for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static CatavigiaProperties properties() {
        Duration second = Duration.ofSeconds(1);
        return new CatavigiaProperties(
                new CatavigiaProperties.Pulsar("http://localhost:8080", second, second, second,
                        "pulsar://localhost:6650", "", second, second),
                new CatavigiaProperties.Mongo(second, second, second),
                new CatavigiaProperties.Tasks("catavigia", 1, second, second, second, Duration.ofHours(6)),
                new CatavigiaProperties.Checks(second, second, 1, Duration.ofSeconds(30)),
                new CatavigiaProperties.Apps("http://catapulsar:8081", "http://cataponte:8082",
                        "http://catametricas:8083", "http://cataudit:8084"),
                new CatavigiaProperties.Api(50, 500));
    }
}
