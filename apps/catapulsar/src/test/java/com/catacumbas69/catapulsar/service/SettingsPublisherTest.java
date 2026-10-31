package com.catacumbas69.catapulsar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SettingsPublisherTest {

    @SuppressWarnings("unchecked")
    private final RetryingSender<SettingValue> sender = mock(RetryingSender.class);
    private final SettingRepository settings = mock(SettingRepository.class);
    private final SettingsPublisher publisher = new SettingsPublisher(sender, settings);

    @Test
    void changed_sendsTheValue_underTheTopicKey() {
        publisher.changed(Setting.CATAPONTE_URL, "http://cataponte:8082");

        verify(sender).send(Setting.CATAPONTE_URL_KEY, new SettingValue("http://cataponte:8082"));
    }

    @Test
    void changed_toDefault_sendsATombstone() {
        publisher.changed(Setting.REFRESH_INTERVAL_SECONDS, null);

        verify(sender).send(Setting.REFRESH_INTERVAL_SECONDS_KEY, null);
    }

    @Test
    void changed_ignoresUnknownSettings() {
        publisher.changed("something.else", "1");

        verify(sender, never()).send(any(), any());
    }

    @Test
    void publishAll_sendsSavedValues_andTombstonesForDefaults() {
        when(settings.findAll()).thenReturn(List.of(
                new Setting(Setting.COLLECT_INTERVAL_SECONDS, "30", Instant.EPOCH, "10.0.0.1")));

        publisher.publishAll();

        verify(sender).send(Setting.COLLECT_INTERVAL_SECONDS_KEY, new SettingValue("30"));
        verify(sender).send(Setting.CATAPONTE_URL_KEY, null);
        verify(sender).send(Setting.REFRESH_INTERVAL_SECONDS_KEY, null);
    }

    @Test
    void publishAll_sendsNothing_whenMongoIsDown() {
        when(settings.findAll()).thenThrow(new DataAccessResourceFailureException("down"));

        publisher.publishAll();

        verify(sender, never()).send(any(), isNull());
        verify(sender, never()).send(any(), any(SettingValue.class));
    }
}
