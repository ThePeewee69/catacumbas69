package com.catacumbas69.catasonarr;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.controller.SystemController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The context starts without MongoDB or Pulsar running (RES-1): nothing connects at start-up.
 */
@SpringBootTest(properties = {
        "PULSAR_SERVICE_URL=http://127.0.0.1:1",
        "PULSAR_BROKER_URL=pulsar://127.0.0.1:1",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/test",
        "catasonarr.mongo.server-selection-timeout=500ms",
        "logging.level.org.mongodb.driver=ERROR"
})
class CatasonarrApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStarts_withoutAnyDependencyRunning() {
        assertThat(context.getBean(SystemController.class)).isNotNull();
    }
}
