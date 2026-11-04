package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Topics;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.junit.jupiter.api.Test;

class PulsarAdminServiceTest {

    private static final String TOPIC = "persistent://home/media/events";

    private final PulsarAdmin pulsarAdmin = mock(PulsarAdmin.class);
    private final Topics topics = mock(Topics.class);
    private final PulsarAdminService service = new PulsarAdminService(pulsarAdmin, mock(ProducerCache.class));

    PulsarAdminServiceTest() {
        when(pulsarAdmin.topics()).thenReturn(topics);
    }

    @Test
    void createTopic_explainsAPartitionedTopicWithTheSameName() throws Exception {
        doThrow(mock(PulsarAdminException.ConflictException.class)).when(topics).createNonPartitionedTopic(TOPIC);
        when(topics.getPartitionedTopicMetadata(TOPIC)).thenReturn(new PartitionedTopicMetadata(1));

        assertThatThrownBy(() -> service.createTopic(TOPIC))
                .isInstanceOf(PartitionedTopicExistsException.class)
                .hasMessageContaining("partitioned topic with 1 partition(s)");
    }

    @Test
    void createTopic_keepsTheConflict_whenTheTopicIsNotPartitioned() throws Exception {
        doThrow(mock(PulsarAdminException.ConflictException.class)).when(topics).createNonPartitionedTopic(TOPIC);
        when(topics.getPartitionedTopicMetadata(TOPIC)).thenReturn(new PartitionedTopicMetadata(0));

        assertThatThrownBy(() -> service.createTopic(TOPIC)).isInstanceOf(PulsarAdminException.ConflictException.class);
    }
}
