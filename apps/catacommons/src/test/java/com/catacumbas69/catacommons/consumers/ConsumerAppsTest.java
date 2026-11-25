package com.catacumbas69.catacommons.consumers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.junit.jupiter.api.Test;

class ConsumerAppsTest {

    @Test
    void discover_theAppsWhoseNamespaceHasAConsumersTopic() throws Exception {
        PulsarAdmin admin = mock(PulsarAdmin.class, RETURNS_DEEP_STUBS);
        when(admin.namespaces().getNamespaces("catacumbas69"))
                .thenReturn(List.of("catacumbas69/catavigia", "catacumbas69/cataponte", "catacumbas69/catasonarr"));
        when(admin.topics().getList("catacumbas69/catavigia")).thenReturn(List.of(
                "persistent://catacumbas69/catavigia/events", "persistent://catacumbas69/catavigia/consumers"));
        when(admin.topics().getList("catacumbas69/cataponte"))
                .thenReturn(List.of("persistent://catacumbas69/cataponte/events"));
        when(admin.topics().getList("catacumbas69/catasonarr"))
                .thenReturn(List.of("persistent://catacumbas69/catasonarr/consumers"));

        assertThat(ConsumerApps.discover(admin)).containsExactly("catasonarr", "catavigia");
    }
}
