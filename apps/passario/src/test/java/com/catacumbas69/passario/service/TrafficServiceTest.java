package com.catacumbas69.passario.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.catacumbas69.passario.repository.BridgeTrafficMinuteRepository;
import com.catacumbas69.passario.repository.BridgeTrafficTotalRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrafficServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final BridgeTrafficMinuteRepository minutes = mock(BridgeTrafficMinuteRepository.class);
    private final TrafficService service = new TrafficService(mock(BridgeTrafficTotalRepository.class), minutes);

    @Test
    void minutes_queriesTheRequestedRange() {
        service.minutes("1", NOW.minus(Duration.ofHours(1)), NOW);

        verify(minutes).findByBridgeIdAndMinuteGreaterThanEqualAndMinuteLessThanOrderByMinuteAsc(
                "1", NOW.minus(Duration.ofHours(1)), NOW);
    }

    @Test
    void minutes_rejectsReversedRange() {
        assertThatThrownBy(() -> service.minutes("1", NOW, NOW.minusSeconds(1)))
                .isInstanceOf(BridgeValidationException.class)
                .extracting("field").isEqualTo("from");
    }

    @Test
    void minutes_rejectsRangeLongerThanSevenDays() {
        assertThatThrownBy(() -> service.minutes("1", NOW.minus(Duration.ofDays(8)), NOW))
                .isInstanceOf(BridgeValidationException.class)
                .extracting("field").isEqualTo("to");
    }
}
