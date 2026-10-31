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

class PassarioEndpointTest {

    private static final String DEFAULT_URL = TestProperties.properties().passario().url();

    private final SettingRepository settings = mock(SettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PassarioEndpoint endpoint = new PassarioEndpoint(TestProperties.properties(), settings, audit,
            Clock.fixed(Instant.parse("2026-10-30T22:00:00Z"), ZoneOffset.UTC));

    @Test
    void startsWithTheConfiguredUrl() {
        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        assertThat(endpoint.isCustom()).isFalse();
    }

    @Test
    void change_storesAppliesAndAudits() {
        String url = endpoint.change(" http://passario-dev:8082/ ", "10.0.0.1");

        assertThat(url).isEqualTo("http://passario-dev:8082");
        assertThat(endpoint.url()).isEqualTo("http://passario-dev:8082");
        assertThat(endpoint.isCustom()).isTrue();
        verify(settings).save(argThat(setting -> setting.getId().equals(Setting.PASSARIO_URL)
                && setting.getValue().equals("http://passario-dev:8082")
                && setting.getUpdatedBy().equals("10.0.0.1")));
        verify(audit).record(eq("10.0.0.1"), eq(AuditAction.CHANGE_SETTING), isNull(), eq(Map.of(
                "setting", Setting.PASSARIO_URL, "from", DEFAULT_URL, "to", "http://passario-dev:8082")));
    }

    @Test
    void reset_deletesTheSavedUrl_andAudits() {
        endpoint.change("http://passario-dev:8082", "10.0.0.1");

        endpoint.reset("10.0.0.2");

        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        verify(settings).deleteById(Setting.PASSARIO_URL);
        verify(audit).record(eq("10.0.0.2"), eq(AuditAction.CHANGE_SETTING), isNull(), any());
    }

    @Test
    void change_doesNotApplyOrAudit_whenMongoFails() {
        when(settings.save(any())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> endpoint.change("http://passario-dev:8082", "10.0.0.1"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidUrls() {
        for (String invalid : new String[] {"", "passario:8082", "ftp://passario", "http://", "http://a/?x=1",
                "http://user:pw@passario", "not a url"}) {
            assertThatThrownBy(() -> endpoint.change(invalid, "10.0.0.1"))
                    .as(invalid)
                    .isInstanceOf(InvalidSettingException.class);
        }
        verify(settings, never()).save(any());
    }

    @Test
    void loadSaved_appliesTheStoredUrl() {
        when(settings.findById(Setting.PASSARIO_URL)).thenReturn(Optional.of(
                new Setting(Setting.PASSARIO_URL, "http://saved:8082", Instant.EPOCH, "10.0.0.1")));

        endpoint.loadSaved();

        assertThat(endpoint.url()).isEqualTo("http://saved:8082");
    }

    @Test
    void loadSaved_keepsTheDefault_whenMongoIsDown() {
        when(settings.findById(Setting.PASSARIO_URL)).thenThrow(new DataAccessResourceFailureException("down"));

        endpoint.loadSaved();

        assertThat(endpoint.url()).isEqualTo(DEFAULT_URL);
    }

    @Test
    void loadSaved_doesNotOverwriteAChangeMadeMeanwhile() {
        endpoint.change("http://new:8082", "10.0.0.1");
        when(settings.findById(Setting.PASSARIO_URL)).thenReturn(Optional.of(
                new Setting(Setting.PASSARIO_URL, "http://old:8082", Instant.EPOCH, "10.0.0.1")));

        endpoint.loadSaved();

        assertThat(endpoint.url()).isEqualTo("http://new:8082");
    }
}
