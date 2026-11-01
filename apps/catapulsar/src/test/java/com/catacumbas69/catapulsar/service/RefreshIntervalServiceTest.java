package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catapulsar.TestProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RefreshIntervalServiceTest {

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettingsPublisher publisher = mock(SettingsPublisher.class);
    private final SharedSettings shared = new SharedSettings(null, Executors.newSingleThreadScheduledExecutor(),
            Duration.ofSeconds(1));
    private final RefreshIntervalService service = new RefreshIntervalService(TestProperties.properties(), settings,
            audit, publisher, shared, Clock.fixed(Instant.parse("2026-10-31T08:30:00Z"), ZoneOffset.UTC));

    @Test
    void usesTheDefault_untilTheTopicHasAValue() {
        assertThat(service.value()).isEqualTo(10);
    }

    @Test
    void followsTheSettingsTopic() {
        shared.apply(Setting.REFRESH_INTERVAL_SECONDS, new SettingValue("30"));
        assertThat(service.value()).isEqualTo(30);

        shared.apply(Setting.REFRESH_INTERVAL_SECONDS, null);
        assertThat(service.value()).isEqualTo(10);
    }

    @Test
    void ignoresInvalidValuesFromTheTopic() {
        shared.apply(Setting.REFRESH_INTERVAL_SECONDS, new SettingValue("1"));
        assertThat(service.value()).isEqualTo(10);

        shared.apply(Setting.REFRESH_INTERVAL_SECONDS, new SettingValue("abc"));
        assertThat(service.value()).isEqualTo(10);
    }

    @Test
    void change_storesAppliesAuditsAndPublishes() {
        int seconds = service.change(30, "10.0.0.1");

        assertThat(seconds).isEqualTo(30);
        assertThat(service.value()).isEqualTo(30);
        verify(settings).save(argThat(setting -> setting.getId().equals(Setting.REFRESH_INTERVAL_SECONDS)
                && setting.getValue().equals("30") && setting.getUpdatedBy().equals("10.0.0.1")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.REFRESH_INTERVAL_SECONDS, "from", 10, "to", 30)));
        verify(publisher).changed(Setting.REFRESH_INTERVAL_SECONDS, "30");
    }

    @Test
    void reset_deletesTheSavedValue_andPublishesATombstone() {
        shared.apply(Setting.REFRESH_INTERVAL_SECONDS, new SettingValue("30"));

        service.reset("10.0.0.2");

        verify(settings).deleteById(Setting.REFRESH_INTERVAL_SECONDS);
        verify(audit).record(eq("10.0.0.2"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.REFRESH_INTERVAL_SECONDS, "from", 30, "to", 10)));
        verify(publisher).changed(Setting.REFRESH_INTERVAL_SECONDS, null);
    }

    @Test
    void change_rejectsValuesOutOfRange() {
        for (int invalid : new int[] {0, 1, 301}) {
            assertThatThrownBy(() -> service.change(invalid, "10.0.0.1"))
                    .as(Integer.toString(invalid))
                    .isInstanceOf(InvalidSettingException.class)
                    .extracting("field").isEqualTo("seconds");
        }
        verify(settings, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any());
        verify(publisher, never()).changed(any(), any());
    }
}
