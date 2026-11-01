package com.catacumbas69.catacommons.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuditBridgeRegistrarTest {

    private final SystemBridgeRegistry registry = mock(SystemBridgeRegistry.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AuditBridgeRegistrar registrar = new AuditBridgeRegistrar(registry, "catapulsar", scheduler,
            Duration.ofMillis(10), Duration.ofMillis(20));

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void registersTheAuditBridgeOfTheApp() {
        assertThat(registrar.bridge().name()).isEqualTo("audit-catapulsar");
        assertThat(registrar.bridge().sourceTopic()).isEqualTo("persistent://catacumbas69/catapulsar/events");
        assertThat(registrar.bridge().destinationTopic()).isEqualTo("persistent://catacumbas69/cataudit/audit");
        assertThat(registrar.bridge().subscription()).isEqualTo("cataponte-audit-catapulsar");
    }

    @Test
    void retries_untilCataponteAccepts() {
        doThrow(new IllegalStateException("Connection refused"))
                .doThrow(new IllegalStateException("Connection refused"))
                .doNothing()
                .when(registry).register(any());

        registrar.onApplicationEvent(null);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(registrar::isRegistered);
        verify(registry, times(3)).register(registrar.bridge());
    }

    @Test
    void registersOnce_whenCataponteIsUp() {
        doNothing().when(registry).register(any());

        registrar.onApplicationEvent(null);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(registrar::isRegistered);
        verify(registry, times(1)).register(any());
    }
}
