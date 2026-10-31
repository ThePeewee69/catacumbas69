package com.catacumbas69.passario.service;

import com.catacumbas69.passario.config.PassarioProperties;
import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps running {@link BridgeWorker}s in line with the {@code bridges} collection (ARCH-4).
 *
 * <p>Every {@code passario.bridge.reconcile-interval}:
 * <ul>
 *   <li>enabled bridge without a worker → start it, unless it is backing off after a failure (RES-5);</li>
 *   <li>enabled bridge whose stored version differs from its worker's → stop it; the next tick starts the
 *   new definition once the old worker has released its subscription;</li>
 *   <li>disabled or deleted bridge with a worker → stop it.</li>
 * </ul>
 * Starting and stopping never block the reconcile thread, and a failure in one bridge never affects
 * another (RES-4). If MongoDB cannot be read, running workers are left alone (RES-6).
 *
 * <p>Enabled bridges that form a message loop (ADR 0005) are never started — the API refuses such
 * changes, but MongoDB can be edited directly — and are reported as {@link BridgeState#BLOCKED}.
 */
@Slf4j
@Service
public class BridgeManager {

    private final BridgeRepository repository;
    private final BridgeWorkerFactory workerFactory;
    private final PassarioProperties.Bridge settings;
    private final Clock clock;

    private final Map<String, BridgeWorker> workers = new ConcurrentHashMap<>();
    private final Set<String> stopping = ConcurrentHashMap.newKeySet();
    private final Map<String, StartFailure> failures = new ConcurrentHashMap<>();
    private final Map<String, BridgeDocument> blocked = new ConcurrentHashMap<>();
    private final ExecutorService stopExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean shuttingDown;

    /**
     * Creates the manager.
     *
     * @param repository bridge definitions
     * @param workerFactory creates workers
     * @param properties application properties
     * @param clock time source
     * @param meterRegistry registry for the {@code passario.bridges.running} gauge
     */
    public BridgeManager(BridgeRepository repository, BridgeWorkerFactory workerFactory,
                         PassarioProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.workerFactory = workerFactory;
        this.settings = properties.bridge();
        this.clock = clock;
        Gauge.builder("passario.bridges.running", this, BridgeManager::getRunningCount)
                .description("Bridges currently relaying messages")
                .register(meterRegistry);
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    /** Applies the desired state from MongoDB. Scheduled; safe to call directly (tests). */
    @Scheduled(fixedDelayString = "${passario.bridge.reconcile-interval}")
    public void reconcile() {
        if (shuttingDown) {
            return;
        }
        List<BridgeDocument> desired;
        try {
            desired = repository.findAll();
        } catch (RuntimeException e) {
            log.warn("Cannot read bridges, keeping {} worker(s) as they are: {}", workers.size(), e.getMessage());
            return;
        }
        Map<String, BridgeDocument> enabled = desired.stream()
                .filter(BridgeDocument::isEnabled)
                .collect(Collectors.toMap(BridgeDocument::getId, Function.identity()));
        updateBlocked(enabled);

        List.copyOf(workers.keySet()).stream()
                .filter(id -> !enabled.containsKey(id) || blocked.containsKey(id))
                .forEach(this::stopAsync);
        failures.keySet().removeIf(id -> !enabled.containsKey(id) || blocked.containsKey(id));
        enabled.values().stream()
                .filter(bridge -> !blocked.containsKey(bridge.getId()))
                .forEach(this::reconcileBridge);
    }

    private void updateBlocked(Map<String, BridgeDocument> enabled) {
        Set<String> looping = BridgeTopology.bridgesInLoops(enabled.values());
        looping.forEach(id -> {
            if (blocked.put(id, enabled.get(id)) == null) {
                log.warn("Bridge '{}' is part of a message loop and will not run until the loop is removed",
                        enabled.get(id).getName());
            }
        });
        blocked.keySet().removeIf(id -> !looping.contains(id));
    }

    private void reconcileBridge(BridgeDocument bridge) {
        try {
            String id = bridge.getId();
            if (stopping.contains(id)) {
                return;
            }
            BridgeWorker worker = workers.get(id);
            if (worker == null) {
                startIfDue(bridge);
            } else if (!Objects.equals(worker.getConfigVersion(), bridge.getVersion())) {
                log.info("Bridge '{}' changed (version {} → {}), restarting", bridge.getName(),
                        worker.getConfigVersion(), bridge.getVersion());
                failures.remove(id);
                stopAsync(id);
            }
        } catch (RuntimeException e) {
            log.error("Reconcile failed for bridge '{}'", bridge.getName(), e);
        }
    }

    private void startIfDue(BridgeDocument bridge) {
        String id = bridge.getId();
        StartFailure previous = failures.get(id);
        if (previous != null && !previous.isDue(clock.instant())) {
            return;
        }
        BridgeWorker worker;
        CompletableFuture<Void> started;
        try {
            worker = workerFactory.create(bridge);
            workers.put(id, worker);
            started = worker.start();
        } catch (RuntimeException e) {
            workers.remove(id);
            recordStartFailure(bridge, previous, BridgeWorker.rootMessage(e));
            return;
        }
        started.whenComplete((ignored, failure) -> {
            if (failure == null) {
                failures.remove(id);
                return;
            }
            workers.remove(id, worker);
            stopExecutor.execute(() -> worker.stop(settings.shutdownTimeout()));
            recordStartFailure(bridge, previous, BridgeWorker.rootMessage(failure));
        });
    }

    private void recordStartFailure(BridgeDocument bridge, StartFailure previous, String error) {
        StartFailure next = StartFailure.next(previous, bridge, error, clock.instant(),
                settings.startBackoffInitial(), settings.startBackoffMax());
        failures.put(bridge.getId(), next);
        log.warn("Bridge '{}' failed to start (attempt {}), retrying at {}: {}",
                bridge.getName(), next.attempts(), next.nextAttemptAt(), error);
    }

    private void stopAsync(String id) {
        BridgeWorker worker = workers.get(id);
        if (worker == null || !stopping.add(id)) {
            return;
        }
        stopExecutor.execute(() -> {
            try {
                worker.stop(settings.shutdownTimeout());
            } catch (RuntimeException e) {
                log.error("Error while stopping bridge {}", id, e);
            } finally {
                workers.remove(id, worker);
                stopping.remove(id);
            }
        });
    }

    /** Stops every worker in parallel, waiting at most the shutdown timeout (RES-7). */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("Stopping {} bridge(s)", workers.size());
        List.copyOf(workers.keySet()).forEach(this::stopAsync);
        stopExecutor.shutdown();
        try {
            long waitMillis = settings.shutdownTimeout().plus(Duration.ofSeconds(5)).toMillis();
            if (!stopExecutor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("Some bridges did not stop in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    /**
     * Returns the status of every running, starting or failed bridge.
     *
     * @return one status per bridge known to the runtime
     */
    public List<BridgeWorkerStatus> getStatuses() {
        List<BridgeWorkerStatus> statuses = new ArrayList<>();
        workers.values().forEach(worker -> statuses.add(worker.getStatus()));
        blocked.values().forEach(bridge -> statuses.add(new BridgeWorkerStatus(bridge.getId(), bridge.getName(),
                BridgeState.BLOCKED, false, 0, 0, 0, null, bridge.getSource().getTopic(),
                bridge.resolveDestinationTopics(), bridge.isOrderedEffective(),
                "Part of a message loop; change the bridges so that no topic feeds back into itself", null)));
        failures.forEach((id, failure) -> {
            if (!workers.containsKey(id) && !blocked.containsKey(id)) {
                statuses.add(new BridgeWorkerStatus(id, failure.bridgeName(), BridgeState.FAILED, false, 0, 0, 0,
                        null, failure.sourceTopic(), failure.destinationTopics(), failure.ordered(),
                        failure.lastError(), failure.nextAttemptAt()));
            }
        });
        return statuses;
    }

    /**
     * Returns how many bridges are relaying messages.
     *
     * @return number of workers in state {@link BridgeState#RUNNING}
     */
    public int getRunningCount() {
        return (int) workers.values().stream().filter(worker -> worker.getState() == BridgeState.RUNNING).count();
    }

    /**
     * Returns how many enabled bridges are not started because they form a message loop.
     *
     * @return number of blocked bridges
     */
    public int getBlockedCount() {
        return blocked.size();
    }

    /**
     * Returns how many enabled bridges are waiting to retry after a failed start.
     *
     * @return number of failed bridges
     */
    public int getFailedCount() {
        return (int) failures.keySet().stream().filter(id -> !workers.containsKey(id)).count();
    }
}
