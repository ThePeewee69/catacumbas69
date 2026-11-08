package com.catacumbas69.catacommons.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.settings.SharedSettings;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlertSettingTest {

    @Test
    void parse_keepsAValueInRange_andFallsBackOtherwise() {
        assertThat(AlertSetting.DEBOUNCE.parse(" 3 ")).isEqualTo(3);
        assertThat(AlertSetting.DEBOUNCE.parse("11")).isEqualTo(2);
        assertThat(AlertSetting.DEBOUNCE.parse("two")).isEqualTo(2);
        assertThat(AlertSetting.DEBOUNCE.parse(null)).isEqualTo(2);
    }

    @Test
    void value_readsTheSettingsTopic_orUsesTheDefault() {
        SharedSettings settings = mock(SharedSettings.class);
        when(settings.get("alerts.backlog-limit")).thenReturn(Optional.of("500"));
        when(settings.get("alerts.check-interval")).thenReturn(Optional.empty());

        assertThat(AlertSetting.BACKLOG_LIMIT.value(settings)).isEqualTo(500);
        assertThat(AlertSetting.CHECK_INTERVAL.value(settings)).isEqualTo(30);
        assertThat(AlertSetting.CHECK_INTERVAL.value(null)).isEqualTo(30);
    }
}
