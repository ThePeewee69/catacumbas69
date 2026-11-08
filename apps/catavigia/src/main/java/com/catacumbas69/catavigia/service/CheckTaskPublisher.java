package com.catacumbas69.catavigia.service;

import com.catacumbas69.catacommons.alerts.AlertSetting;
import com.catacumbas69.catacommons.pulsar.RetryingSender;
import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.model.CheckTask;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Publishes the tasks of a round: one per check (orch.md decision 75). Every replica publishes the same ids; the
 * claim keeps each check single.
 */
@Component
public class CheckTaskPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(CheckTaskPublisher.class);

    private final CheckRunner runner;
    private final RetryingSender<CheckTask> sender;
    private final SharedSettings settings;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param runner knows the checks
     * @param sender sends to the task topic
     * @param settings the settings topic, for the interval
     * @param clock time source
     */
    public CheckTaskPublisher(CheckRunner runner, RetryingSender<CheckTask> sender,
                              ObjectProvider<SharedSettings> settings, Clock clock) {
        this.runner = runner;
        this.sender = sender;
        this.settings = settings.getIfAvailable();
        this.clock = clock;
    }

    /**
     * Returns the round length in use.
     *
     * @return seconds ({@code alerts.check-interval})
     */
    public int intervalSeconds() {
        return (int) AlertSetting.CHECK_INTERVAL.value(settings);
    }

    /**
     * Returns the start of the round that contains a time. Rounds are aligned to the epoch, so every replica
     * computes the same start.
     *
     * @param time a time
     * @param seconds round length
     * @return the start of its round
     */
    public static Instant roundOf(Instant time, int seconds) {
        return Instant.ofEpochSecond(Math.floorDiv(time.getEpochSecond(), seconds) * seconds);
    }

    /** Publishes one task per check for the current round. Never throws. */
    public void publish() {
        int seconds = intervalSeconds();
        Instant round = roundOf(clock.instant(), seconds);
        for (String check : runner.names()) {
            CheckTask task = CheckTask.of(check, round, seconds);
            sender.send(task.id(), task);
        }
        LOG.debug("Published {} check task(s) for {}", runner.names().size(), round);
    }
}
