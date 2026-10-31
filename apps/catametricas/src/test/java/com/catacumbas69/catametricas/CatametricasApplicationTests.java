package com.catacumbas69.catametricas;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catametricas.service.StatsCollector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The context starts without MongoDB or Pulsar running (RES-1): nothing connects at start-up and the
 * first collection only runs after the initial delay.
 */
@SpringBootTest(properties = {
        "PULSAR_SERVICE_URL=http://127.0.0.1:1",
        "PULSAR_BROKER_URL=pulsar://127.0.0.1:1",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/test",
        "catametricas.collect.initial-delay=1h",
        "catametricas.mongo.server-selection-timeout=500ms",
        "logging.level.org.mongodb.driver=ERROR"
})
class CatametricasApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStarts_withoutAnyDependencyRunning() {
        assertThat(context.getBean(StatsCollector.class)).isNotNull();
    }
}
