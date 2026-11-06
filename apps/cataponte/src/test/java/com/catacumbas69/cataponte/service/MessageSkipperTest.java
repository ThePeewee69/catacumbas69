package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.List;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.Topics;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class MessageSkipperTest {

    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Topics topics = mock(Topics.class);
    private final MessageSkipper skipper = new MessageSkipper(admin);
    private final BridgeDocument bridge = TestBridges.ordered("1", TestBridges.DEST_A);

    MessageSkipperTest() throws Exception {
        when(admin.topics()).thenReturn(topics);
        when(topics.getPartitionedTopicMetadata(anyString())).thenReturn(new PartitionedTopicMetadata(0));
    }

    @Test
    void skipsTheFirstMessage_whenItIsTheStuckOne() throws Exception {
        head("12:34:-1");

        skipper.skip(bridge, "12:34:-1");

        verify(topics).skipMessages(TestBridges.SOURCE, "cataponte-1", 1);
    }

    @Test
    void refuses_whenTheFirstMessageIsAnotherOne() throws Exception {
        head("12:35:-1");

        assertThatThrownBy(() -> skipper.skip(bridge, "12:34:-1")).isInstanceOf(BridgeConflictException.class);
        verify(topics, never()).skipMessages(anyString(), anyString(), anyLong());
    }

    @Test
    void refuses_aPartitionedSourceTopic() throws Exception {
        when(topics.getPartitionedTopicMetadata(anyString())).thenReturn(new PartitionedTopicMetadata(3));

        assertThatThrownBy(() -> skipper.skip(bridge, "12:34:-1")).isInstanceOf(BridgeValidationException.class);
        verify(topics, never()).peekMessages(anyString(), anyString(), anyInt());
    }

    @Test
    void comparesLedgerAndEntryOnly() {
        assertThat(MessageSkipper.sameEntry("12:34:-1:3", "12:34:-1")).isTrue();
        assertThat(MessageSkipper.sameEntry("12:34:-1", "12:35:-1")).isFalse();
        assertThat(MessageSkipper.sameEntry("x", "12:34:-1")).isFalse();
    }

    private void head(String id) throws Exception {
        Message<byte[]> message = mock(Message.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(id);
        when(message.getMessageId()).thenReturn(messageId);
        when(topics.peekMessages(TestBridges.SOURCE, "cataponte-1", 1)).thenReturn(List.of(message));
    }
}
