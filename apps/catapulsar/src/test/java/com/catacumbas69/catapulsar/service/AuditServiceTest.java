package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catacommons.events.EventData;
import com.catacumbas69.catacommons.events.EventPublisher;
import com.catacumbas69.catapulsar.model.Setting;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    private final EventPublisher events = mock(EventPublisher.class);
    private final AuditService audit = new AuditService(events);

    @Test
    void recordMessage_publishesTheMatchingEvent() {
        audit.recordMessage("10.0.0.1", AuditAction.PRODUCE, "persistent://a/b/c", "{}", "k", Map.of("x", 1));

        verify(events).publish("message", "produced", "persistent://a/b/c",
                EventData.action("10.0.0.1", Map.of("x", 1, "key", "k", "payload", "{}")));
    }

    @Test
    void record_publishesTopicActions_keyedByTopic() {
        audit.record("10.0.0.1", AuditAction.CLEAR_BACKLOG, "persistent://a/b/c", Map.of("subscription", "s1"));

        verify(events).publish("subscription", "backlog-cleared", "persistent://a/b/c",
                EventData.action("10.0.0.1", Map.of("subscription", "s1")));
    }

    @Test
    void record_settingChange_publishesBeforeAndAfter_keyedByTopicKey() {
        audit.record("10.0.0.1", AuditAction.CHANGE_SETTING, null,
                Map.of("setting", Setting.COLLECT_INTERVAL_SECONDS, "from", 60, "to", 30));

        verify(events).publish("setting", "changed", Setting.COLLECT_INTERVAL_SECONDS,
                EventData.change("10.0.0.1", Map.of("value", 60), Map.of("value", 30), null));
    }

    @Test
    void record_neverFails_whenPublishingFails() {
        doThrow(new IllegalStateException("boom")).when(events).publish(any(), any(), any(), any());

        assertThatCode(() -> audit.record("10.0.0.1", AuditAction.CREATE_TOPIC, "t", null))
                .doesNotThrowAnyException();
    }
}
