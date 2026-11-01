package com.catacumbas69.cataudit;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.cataudit.controller.AuditController;
import com.catacumbas69.cataudit.service.AuditConsumer;
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
        "cataudit.mongo.server-selection-timeout=500ms",
        "logging.level.org.mongodb.driver=ERROR"
})
class CatauditApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStarts_withoutAnyDependencyRunning() {
        assertThat(context.getBean(AuditController.class)).isNotNull();
        assertThat(context.getBean(AuditConsumer.class).isConnected()).isFalse();
    }
}
