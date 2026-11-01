package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.catapulsar.TestProperties;
import com.catacumbas69.catapulsar.model.Setting;
import com.catacumbas69.catapulsar.repository.SettingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatauditEndpointTest {

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettingsPublisher publisher = mock(SettingsPublisher.class);
    private final CatauditEndpoint endpoint = new CatauditEndpoint(TestProperties.properties(), settings, audit,
            publisher, Clock.fixed(Instant.parse("2026-11-01T00:30:00Z"), ZoneOffset.UTC));

    @Test
    void startsWithTheConfiguredUrl() {
        assertThat(endpoint.url()).isEqualTo("http://localhost:8084");
        assertThat(endpoint.isCustom()).isFalse();
    }

    @Test
    void change_storesAuditsAndPublishes_underItsOwnId() {
        endpoint.change("http://cataudit-dev:8084/", "10.0.0.1");

        assertThat(endpoint.url()).isEqualTo("http://cataudit-dev:8084");
        verify(settings).save(argThat(setting -> setting.getId().equals("catapulsar.cataudit-url")
                && setting.getValue().equals("http://cataudit-dev:8084")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.CATAUDIT_URL, "from", "http://localhost:8084", "to", "http://cataudit-dev:8084")));
        verify(publisher).changed(Setting.CATAUDIT_URL, "http://cataudit-dev:8084");
    }

    @Test
    void rejectsInvalidUrls() {
        assertThatThrownBy(() -> endpoint.change("http://", "10.0.0.1"))
                .isInstanceOf(InvalidSettingException.class);
    }
}
