package com.catacumbas69.passario.controller;

import com.catacumbas69.passario.document.BridgeDestination;
import com.catacumbas69.passario.document.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import com.catacumbas69.passario.service.BridgeManager;
import com.catacumbas69.passario.service.BridgeWorkerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bridges")
@RequiredArgsConstructor
public class BridgeController {

    private final BridgeRepository repository;
    private final BridgeManager manager;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping
    public List<BridgeDocument> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<BridgeDocument> get(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BridgeDocument bridge) {
        String error = validate(bridge);
        if (error != null) return ResponseEntity.badRequest().body(Map.of("message", error));
        normalize(bridge);
        bridge.setId(null);
        bridge.setCreatedAt(Instant.now());
        bridge.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(repository.save(bridge));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable String id,
            @RequestBody BridgeDocument bridge) {
        String error = validate(bridge);
        if (error != null) return ResponseEntity.badRequest().body(Map.of("message", error));
        return repository.findById(id).<ResponseEntity<?>>map(existing -> {
            normalize(bridge);
            bridge.setId(id);
            bridge.setCreatedAt(existing.getCreatedAt());
            bridge.setUpdatedAt(Instant.now());
            return ResponseEntity.ok(repository.save(bridge));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private String validate(BridgeDocument b) {
        if (b.getName() == null || b.getName().isBlank()) return "Name is required";
        if (b.getSource() == null || b.getSource().getTopic() == null || b.getSource().getTopic().isBlank())
            return "Source topic is required";
        if (b.getSource().getSubscription() == null || b.getSource().getSubscription().isBlank())
            return "Subscription name is required";
        List<String> dests = b.resolveDestinationTopics();
        if (dests.isEmpty()) return "At least one destination topic is required";
        if (dests.contains(b.getSource().getTopic())) return "A destination cannot be the source topic";
        return null;
    }

    /** Moves a legacy single {@code destination} into {@code destinations} and de-duplicates. */
    private void normalize(BridgeDocument b) {
        List<BridgeDestination> list = b.resolveDestinationTopics().stream().map(t -> {
            BridgeDestination d = new BridgeDestination();
            d.setTopic(t);
            return d;
        }).toList();
        b.setDestinations(new java.util.ArrayList<>(list));
        b.setDestination(null);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repository.existsById(id)) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Enable / Disable ──────────────────────────────────────────────────────

    @PostMapping("/{id}/enable")
    public ResponseEntity<BridgeDocument> enable(@PathVariable String id) {
        return repository.findById(id).map(bridge -> {
            bridge.setEnabled(true);
            bridge.setUpdatedAt(Instant.now());
            return ResponseEntity.ok(repository.save(bridge));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<BridgeDocument> disable(@PathVariable String id) {
        return repository.findById(id).map(bridge -> {
            bridge.setEnabled(false);
            bridge.setUpdatedAt(Instant.now());
            return ResponseEntity.ok(repository.save(bridge));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<BridgeWorkerStatus> workers = manager.getStatuses();
        return Map.of(
                "activeCount", manager.getActiveCount(),
                "workers", workers
        );
    }
}
