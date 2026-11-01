package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final SettingsMigration migration = mock(SettingsMigration.class);
    private final SettingsPublisher publisher = new SettingsPublisher(sender, settings, migration);

    @Test
    void changed_sendsTheValue_underItsId() {
        publisher.changed(Setting.CATAPONTE_URL, "http://cataponte:8082");

        verify(sender).send(Setting.CATAPONTE_URL, new SettingValue("http://cataponte:8082"));
    }

    @Test
    void changed_toDefault_sendsATombstone() {
        publisher.changed(Setting.REFRESH_INTERVAL_SECONDS, null);

        verify(sender).send(Setting.REFRESH_INTERVAL_SECONDS, null);
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

        assertThat(publisher.publishAll()).isTrue();

        verify(migration).migrate();
        verify(sender).send(Setting.COLLECT_INTERVAL_SECONDS, new SettingValue("30"));
        verify(sender).send(Setting.CATAPONTE_URL, null);
        verify(sender).send(Setting.REFRESH_INTERVAL_SECONDS, null);
        verify(sender).send(Setting.CATAUDIT_URL, null);
    }

    @Test
    void publishAll_sendsNothing_whenMongoIsDown() {
        when(settings.findAll()).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(publisher.publishAll()).isFalse();

        verify(sender, never()).send(any(), isNull());
        verify(sender, never()).send(any(), any(SettingValue.class));
    }
}
