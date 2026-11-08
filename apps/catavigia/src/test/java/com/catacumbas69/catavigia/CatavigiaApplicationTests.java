package com.catacumbas69.catavigia;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catavigia.checks.Check;
import com.catacumbas69.catavigia.controller.AlertController;
import com.catacumbas69.catavigia.service.CheckRunner;
import com.catacumbas69.catavigia.service.CheckTaskConsumer;
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
        "catavigia.mongo.server-selection-timeout=500ms",
        "catavigia.checks.initial-delay=1h",
        "logging.level.org.mongodb.driver=ERROR"
})
class CatavigiaApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStarts_withoutAnyDependencyRunning_withEveryCheck() {
        assertThat(context.getBean(AlertController.class)).isNotNull();
        assertThat(context.getBean(CheckTaskConsumer.class).isConnected()).isFalse();
        assertThat(context.getBean(CheckRunner.class).names())
                .containsExactly("backlog", "bridges", "collection", "consumers", "poison", "pulsar", "services");
        assertThat(context.getBeansOfType(Check.class)).hasSize(7);
    }
}
