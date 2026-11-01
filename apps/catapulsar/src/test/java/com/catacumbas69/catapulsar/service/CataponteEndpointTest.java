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
import static org.mockito.Mockito.when;

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
import org.springframework.dao.DataAccessResourceFailureException;

class CataponteEndpointTest {

    private static final String DEFAULT_URL = TestProperties.properties().cataponte().url();

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettingsPublisher publisher = mock(SettingsPublisher.class);
    private final SharedSettings shared = new SharedSettings(null, Executors.newSingleThreadScheduledExecutor(),
            Duration.ofSeconds(1));
    private final CataponteEndpoint endpoint = new CataponteEndpoint(TestProperties.properties(), settings, audit,
            publisher, shared, Clock.fixed(Instant.parse("2026-10-30T22:00:00Z"), ZoneOffset.UTC));

    @Test
    void startsWithTheConfiguredUrl() {
        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        assertThat(endpoint.isCustom()).isFalse();
    }

    @Test
    void change_storesAppliesAndAudits() {
        String url = endpoint.change(" http://cataponte-dev:8082/ ", "10.0.0.1");

        assertThat(url).isEqualTo("http://cataponte-dev:8082");
        assertThat(endpoint.url()).isEqualTo("http://cataponte-dev:8082");
        assertThat(endpoint.isCustom()).isTrue();
        verify(settings).save(argThat(setting -> setting.getId().equals(Setting.CATAPONTE_URL)
                && setting.getValue().equals("http://cataponte-dev:8082")
                && setting.getUpdatedBy().equals("10.0.0.1")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.CATAPONTE_URL, "from", DEFAULT_URL, "to", "http://cataponte-dev:8082")));
        verify(publisher).changed(Setting.CATAPONTE_URL, "http://cataponte-dev:8082");
    }

    @Test
    void reset_deletesTheSavedUrl_andAudits() {
        endpoint.change("http://cataponte-dev:8082", "10.0.0.1");

        endpoint.reset("10.0.0.2");

        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        verify(settings).deleteById(Setting.CATAPONTE_URL);
        verify(audit).record(eq("10.0.0.2"), eq(AuditAction.CHANGE_SETTING), isNull(), any());
    }

    @Test
    void change_doesNotApplyOrAudit_whenMongoFails() {
        when(settings.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> endpoint.change("http://cataponte-dev:8082", "10.0.0.1"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidUrls() {
        for (String invalid : new String[] {"", "cataponte:8082", "ftp://cataponte", "http://", "http://a/?x=1",
                "http://user:pw@cataponte", "not a url"}) {
            assertThatThrownBy(() -> endpoint.change(invalid, "10.0.0.1"))
                    .as(invalid)
                    .isInstanceOf(InvalidSettingException.class);
        }
        verify(settings, never()).save(any());
    }

    @Test
    void followsTheSettingsTopic() {
        shared.apply(Setting.CATAPONTE_URL, new SettingValue("http://other:8082/"));
        assertThat(endpoint.url()).isEqualTo("http://other:8082");
        assertThat(endpoint.isCustom()).isTrue();

        shared.apply(Setting.CATAPONTE_URL, null);
        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
    }

    @Test
    void ignoresAnInvalidUrlFromTheTopic() {
        shared.apply(Setting.CATAPONTE_URL, new SettingValue("not a url"));

        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
    }
}
