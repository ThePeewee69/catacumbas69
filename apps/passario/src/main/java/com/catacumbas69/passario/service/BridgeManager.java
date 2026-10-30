package com.catacumbas69.passario.service;

import com.catacumbas69.passario.document.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reconciles active {@link BridgeWorker} instances with the bridge configuration
 * stored in MongoDB. Runs every {@code bridge.reconcile.interval} milliseconds.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Enabled bridges without a running worker → start one.</li>
 *   <li>Disabled or deleted bridges with a running worker → stop it.</li>
 *   <li>Enabled bridges whose config changed (updatedAt bumped) → restart.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BridgeManager {

    private final BridgeRepository repository;
    private final PulsarClient pulsarClient;

    /** bridgeId → active worker */
    private final Map<String, BridgeWorker> activeWorkers = new ConcurrentHashMap<>();

    /** bridgeId → updatedAt at the time the worker was started */
    private final Map<String, Instant> workerStartedWith = new ConcurrentHashMap<>();

    // ── Reconciliation ────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${bridge.reconcile.interval:5000}")
    public void reconcile() {
        List<BridgeDocument> all;
        try {
            all = repository.findAll();
        } catch (Exception e) {
            log.error("[Passário] Cannot read bridges from MongoDB: {}", e.getMessage());
            return;
        }

        Set<String> enabledIds = all.stream()
                .filter(BridgeDocument::isEnabled)
                .map(BridgeDocument::getId)
                .collect(Collectors.toSet());

        // Stop workers for bridges that are disabled or no longer in the collection
        for (String id : List.copyOf(activeWorkers.keySet())) {
            if (!enabledIds.contains(id)) {
                log.info("[Passário] Stopping bridge {}", id);
                stopWorker(id);
            }
        }

        // Start (or restart on config change) enabled bridges
        for (BridgeDocument bridge : all) {
            if (!bridge.isEnabled()) continue;

            String id = bridge.getId();
            BridgeWorker existing = activeWorkers.get(id);
            Instant lastStarted = workerStartedWith.get(id);
            boolean configChanged = bridge.getUpdatedAt() != null && !bridge.getUpdatedAt().equals(lastStarted);

            if (existing == null) {
                startWorker(bridge);
            } else if (configChanged) {
                log.info("[Passário] Config changed for bridge '{}', restarting", bridge.getName());
                stopWorker(id);
                startWorker(bridge);
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void startWorker(BridgeDocument bridge) {
        try {
            BridgeWorker worker = new BridgeWorker(bridge, pulsarClient);
            worker.start();
            activeWorkers.put(bridge.getId(), worker);
            workerStartedWith.put(bridge.getId(), bridge.getUpdatedAt());
        } catch (PulsarClientException e) {
            log.error("[Passário] Failed to start bridge '{}': {}", bridge.getName(), e.getMessage());
        }
    }

    private void stopWorker(String id) {
        BridgeWorker worker = activeWorkers.remove(id);
        workerStartedWith.remove(id);
        if (worker != null) worker.stop();
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Passário] Shutting down {} bridge(s)...", activeWorkers.size());
        activeWorkers.values().forEach(BridgeWorker::stop);
        activeWorkers.clear();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<BridgeWorkerStatus> getStatuses() {
        return activeWorkers.values().stream()
                .map(BridgeWorker::getStatus)
                .toList();
    }

    public int getActiveCount() {
        return activeWorkers.size();
    }
}
