package com.catacumbas69.catametricas.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catacommons.settings.SettingValue;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catametricas.TestProperties;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class CollectIntervalTest {

    private final SharedSettings shared = new SharedSettings(null, Executors.newSingleThreadScheduledExecutor(),
            Duration.ofSeconds(1));
    private final CollectInterval interval = new CollectInterval(shared, TestProperties.properties());

    @Test
    void usesTheValueSetInCatapulsar() {
        shared.apply(CollectInterval.KEY, new SettingValue("120"));

        assertThat(interval.seconds()).isEqualTo(120);
    }

    @Test
    void fallsBackToTheDefault_whenMissingInvalidOrOutOfRange() {
        assertThat(interval.seconds()).isEqualTo(60);
        shared.apply(CollectInterval.KEY, new SettingValue("abc"));
        assertThat(interval.seconds()).isEqualTo(60);
        shared.apply(CollectInterval.KEY, new SettingValue("5"));
        assertThat(interval.seconds()).isEqualTo(60);
        shared.apply(CollectInterval.KEY, new SettingValue("601"));
        assertThat(interval.seconds()).isEqualTo(60);
    }

    @Test
    void goesBackToTheDefault_onATombstone() {
        shared.apply(CollectInterval.KEY, new SettingValue("120"));
        shared.apply(CollectInterval.KEY, null);

        assertThat(interval.seconds()).isEqualTo(60);
    }
}
