package com.catacumbas69.catacommons;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class CatacommonsPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void defaultsAreAcceptedByPulsar() {
        CatacommonsProperties properties = bind(Map.of("catacommons.app", "catapulsar"));

        assertThat(properties.provisioning().eventsRetentionSize()).isEqualTo(DataSize.ofGigabytes(2));
        assertThat(properties.provisioning().eventsBacklogQuota()).isEqualTo(DataSize.ofGigabytes(1));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void backlogQuotaEqualToRetentionIsRefused() {
        CatacommonsProperties properties = bind(Map.of("catacommons.app", "catapulsar",
                "catacommons.provisioning.events-retention-size", "1GB",
                "catacommons.provisioning.events-backlog-quota", "1GB"));

        Set<ConstraintViolation<CatacommonsProperties>> violations = validator.validate(properties);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("provisioning.backlogQuotaBelowRetention");
            assertThat(violation.getMessage()).contains("events-backlog-quota must be smaller");
        });
    }

    @Test
    void backlogQuotaAboveRetentionIsRefused() {
        assertThat(provisioning(DataSize.ofMegabytes(512), DataSize.ofGigabytes(1)).isBacklogQuotaBelowRetention())
                .isFalse();
    }

    @Test
    void retentionIsComparedInWholeMegabytes() {
        DataSize retention = DataSize.ofBytes(DataSize.ofMegabytes(100).toBytes() + 1);

        assertThat(provisioning(retention, DataSize.ofMegabytes(100)).isBacklogQuotaBelowRetention()).isFalse();
    }

    @Test
    void infiniteOrNoRetentionIsNotChecked() {
        assertThat(provisioning(DataSize.ofBytes(-1), DataSize.ofGigabytes(1)).isBacklogQuotaBelowRetention())
                .isTrue();
        assertThat(provisioning(DataSize.ofBytes(0), DataSize.ofGigabytes(1)).isBacklogQuotaBelowRetention())
                .isTrue();
    }

    private static CatacommonsProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("catacommons", CatacommonsProperties.class)
                .get();
    }

    private static CatacommonsProperties.Provisioning provisioning(DataSize retention, DataSize backlogQuota) {
        return new CatacommonsProperties.Provisioning(true, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofDays(7), retention, backlogQuota, DataSize.ofMegabytes(1));
    }
}
