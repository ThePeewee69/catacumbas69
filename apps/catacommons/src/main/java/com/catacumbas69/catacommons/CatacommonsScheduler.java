package com.catacumbas69.catacommons;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The single background thread catacommons uses for retries and provisioning; the work is tiny and never
 * blocks it.
 *
 * <p>Wrapped in its own type on purpose: a {@link ScheduledExecutorService} bean would stop Spring Boot from
 * creating the app's own task scheduler, and {@code @Scheduled} methods would then run on this thread.
 */
public class CatacommonsScheduler implements AutoCloseable {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("catacommons").daemon(true).factory());

    /**
     * Returns the executor.
     *
     * @return the scheduled executor
     */
    public ScheduledExecutorService executor() {
        return executor;
    }

    /** Stops the thread; retries not yet started are dropped. */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
