package com.catacumbas69.pulsario;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.pulsario.controller.BridgeProxyController;
import com.catacumbas69.pulsario.controller.PulsarController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The whole context starts without MongoDB, Pulsar or Passário running (RES-1): no client connects at
 * start-up.
 */
@SpringBootTest(properties = {
        "PULSAR_SERVICE_URL=http://127.0.0.1:1",
        "PULSAR_BROKER_URL=pulsar://127.0.0.1:1",
        "PASSARIO_URL=http://127.0.0.1:1",
        "spring.data.mongodb.uri=mongodb://127.0.0.1:1/test"
})
class PulsarioApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStarts_withoutAnyDependencyRunning() {
        assertThat(context.getBean(PulsarController.class)).isNotNull();
        assertThat(context.getBean(BridgeProxyController.class)).isNotNull();
    }
}
