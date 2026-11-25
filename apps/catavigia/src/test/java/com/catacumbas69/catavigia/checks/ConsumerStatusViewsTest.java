package com.catacumbas69.catavigia.checks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsScheduler;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

class ConsumerStatusViewsTest {

    private final PulsarAdmin admin = mock(PulsarAdmin.class, RETURNS_DEEP_STUBS);
    private final ConsumerStatusViews views = new ConsumerStatusViews(mock(PulsarClient.class), admin,
            mock(CatacommonsScheduler.class, RETURNS_DEEP_STUBS));

    @Test
    void all_isUnavailable_untilTheAppsAreFound_andUntilEachAppsTopicIsRead() throws Exception {
        assertThatThrownBy(views::all).isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("not found yet");

        when(admin.namespaces().getNamespaces("catacumbas69")).thenReturn(List.of("catacumbas69/catasonarr"));
        when(admin.topics().getList("catacumbas69/catasonarr"))
                .thenReturn(List.of("persistent://catacumbas69/catasonarr/consumers"));
        views.discover();

        assertThatThrownBy(views::all).isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("catasonarr not read yet");
    }
}
