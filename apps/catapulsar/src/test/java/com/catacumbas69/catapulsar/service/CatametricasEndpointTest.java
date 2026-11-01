package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

class CatametricasEndpointTest {

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettingsPublisher publisher = mock(SettingsPublisher.class);
    private final SharedSettings shared = new SharedSettings(null, Executors.newSingleThreadScheduledExecutor(),
            Duration.ofSeconds(1));
    private final CatametricasEndpoint endpoint = new CatametricasEndpoint(TestProperties.properties(), settings, audit,
            publisher, shared, Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC));

    @Test
    void startsWithTheConfiguredUrl() {
        assertThat(endpoint.url()).isEqualTo("http://localhost:8083");
        assertThat(endpoint.isCustom()).isFalse();
    }

    @Test
    void change_storesAuditsAndPublishes_underItsOwnId() {
        endpoint.change("http://catametricas-dev:8083/", "10.0.0.1");

        assertThat(endpoint.url()).isEqualTo("http://catametricas-dev:8083");
        verify(settings).save(argThat(setting -> setting.getId().equals("catapulsar.catametricas-url")
                && setting.getValue().equals("http://catametricas-dev:8083")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.CATAMETRICAS_URL, "from", "http://localhost:8083",
                "to", "http://catametricas-dev:8083")));
        verify(publisher).changed(Setting.CATAMETRICAS_URL, "http://catametricas-dev:8083");
    }

    @Test
    void rejectsInvalidUrls() {
        assertThatThrownBy(() -> endpoint.change("http://", "10.0.0.1"))
                .isInstanceOf(InvalidSettingException.class);
    }
}
