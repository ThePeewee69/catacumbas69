package com.catacumbas69.pulsario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.pulsario.TestProperties;
import com.catacumbas69.pulsario.model.Setting;
import com.catacumbas69.pulsario.repository.SettingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RefreshIntervalServiceTest {

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RefreshIntervalService service = new RefreshIntervalService(TestProperties.properties(), settings,
            audit, Clock.fixed(Instant.parse("2026-10-31T08:30:00Z"), ZoneOffset.UTC));

    @Test
    void usesTheDefault_whenNothingIsSaved() {
        assertThat(service.seconds()).isEqualTo(10);
    }

    @Test
    void readsTheSavedValueFromMongo() {
        saved("30");

        assertThat(service.seconds()).isEqualTo(30);
    }

    @Test
    void ignoresInvalidSavedValues() {
        saved("1");
        assertThat(service.seconds()).isEqualTo(10);

        saved("abc");
        assertThat(service.seconds()).isEqualTo(10);
    }

    @Test
    void usesTheDefault_whenMongoIsDown() {
        when(settings.findById(Setting.REFRESH_INTERVAL_SECONDS))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(service.seconds()).isEqualTo(10);
    }

    @Test
    void change_storesAndAudits() {
        int seconds = service.change(30, "10.0.0.1");

        assertThat(seconds).isEqualTo(30);
        verify(settings).save(argThat(setting -> setting.getId().equals(Setting.REFRESH_INTERVAL_SECONDS)
                && setting.getValue().equals("30") && setting.getUpdatedBy().equals("10.0.0.1")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.REFRESH_INTERVAL_SECONDS, "from", 10, "to", 30)));
    }

    @Test
    void reset_deletesTheSavedValue_andAudits() {
        saved("30");

        service.reset("10.0.0.2");

        verify(settings).deleteById(Setting.REFRESH_INTERVAL_SECONDS);
        verify(audit).record(eq("10.0.0.2"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.REFRESH_INTERVAL_SECONDS, "from", 30, "to", 10)));
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
    }

    private void saved(String value) {
        when(settings.findById(Setting.REFRESH_INTERVAL_SECONDS))
                .thenReturn(Optional.of(new Setting(Setting.REFRESH_INTERVAL_SECONDS, value, Instant.EPOCH, "x")));
    }
}
