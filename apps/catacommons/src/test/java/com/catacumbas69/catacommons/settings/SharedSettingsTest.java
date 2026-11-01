package com.catacumbas69.catacommons.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TableView;
import org.apache.pulsar.client.api.TableViewBuilder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("unchecked")
class SharedSettingsTest {

    private final PulsarClient client = mock(PulsarClient.class);
    private final TableViewBuilder<SettingValue> builder = mock(TableViewBuilder.class, RETURNS_SELF);
    private final TableView<SettingValue> view = mock(TableView.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final SharedSettings settings = new SharedSettings(client, scheduler, Duration.ofMillis(10));

    SharedSettingsTest() {
        when(client.newTableView(any(Schema.class))).thenReturn(builder);
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void get_isEmpty_untilAValueArrives_andAfterATombstone() {
        assertThat(settings.get("k")).isEmpty();

        settings.apply("k", new SettingValue("60"));
        assertThat(settings.get("k")).contains("60");

        settings.apply("k", null);
        assertThat(settings.get("k")).isEmpty();
    }

    @Test
    void listen_receivesTheCurrentValue_thenEveryChange() {
        settings.apply("k", new SettingValue("60"));
        List<String> seen = new ArrayList<>();

        settings.listen("k", seen::add);
        settings.apply("k", new SettingValue("30"));
        settings.apply("other", new SettingValue("x"));
        settings.apply("k", null);

        assertThat(seen).containsExactly("60", "30", null);
    }

    @Test
    void opensTheView_retryingWhilePulsarIsDown() {
        when(builder.createAsync()).thenReturn(CompletableFuture.failedFuture(new PulsarClientException("down")),
                CompletableFuture.completedFuture(view));

        settings.onApplicationEvent(null);

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(settings::isReady);
        ArgumentCaptor<BiConsumer<String, SettingValue>> listener = ArgumentCaptor.forClass(BiConsumer.class);
        verify(view).forEachAndListen(listener.capture());
        listener.getValue().accept("catametricas.collect-interval-seconds", new SettingValue("30"));
        assertThat(settings.get("catametricas.collect-interval-seconds")).contains("30");
    }
}
