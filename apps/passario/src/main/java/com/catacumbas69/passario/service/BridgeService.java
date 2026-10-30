package com.catacumbas69.passario.service;

import com.catacumbas69.passario.model.BridgeDestination;
import com.catacumbas69.passario.model.BridgeDocument;
import com.catacumbas69.passario.repository.BridgeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Creates, changes and deletes bridge definitions. Only the stored definition changes here; the
 * {@link BridgeManager} applies it to the running workers (ARCH-4).
 */
@Service
public class BridgeService {

    private static final String SHARED = "Shared";

    private final BridgeRepository repository;

    /**
     * Creates the service.
     *
     * @param repository bridge definitions
     */
    public BridgeService(BridgeRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists every bridge, sorted by name.
     *
     * @return all bridges
     */
    public List<BridgeDocument> findAll() {
        return repository.findAll(Sort.by("name"));
    }

    /**
     * Returns one bridge.
     *
     * @param id bridge id
     * @return the bridge
     * @throws BridgeNotFoundException if it does not exist
     */
    public BridgeDocument get(String id) {
        return repository.findById(id).orElseThrow(() -> new BridgeNotFoundException(id));
    }

    /**
     * Validates and stores a new bridge.
     *
     * @param draft the new definition; id, version and timestamps are ignored
     * @return the stored bridge
     * @throws BridgeValidationException if a cross-field rule is broken
     * @throws BridgeConflictException if the name is taken
     */
    public BridgeDocument create(BridgeDocument draft) {
        normalize(draft);
        validate(draft);
        if (repository.existsByName(draft.getName())) {
            throw duplicateName(draft.getName());
        }
        draft.setId(null);
        draft.setVersion(null);
        draft.setCreatedAt(null);
        draft.setUpdatedAt(null);
        return save(draft);
    }

    /**
     * Replaces the definition of an existing bridge.
     *
     * @param id bridge id
     * @param changes the new definition
     * @param expectedVersion version the client edited; {@code null} skips the check
     * @return the stored bridge
     * @throws BridgeNotFoundException if it does not exist
     * @throws BridgeConflictException if the version is stale or the name is taken
     * @throws BridgeValidationException if a cross-field rule is broken
     */
    public BridgeDocument update(String id, BridgeDocument changes, Long expectedVersion) {
        BridgeDocument existing = get(id);
        if (expectedVersion != null && !expectedVersion.equals(existing.getVersion())) {
            throw staleVersion();
        }
        normalize(changes);
        validate(changes);
        if (repository.existsByNameAndIdNot(changes.getName(), id)) {
            throw duplicateName(changes.getName());
        }
        existing.copyDefinitionFrom(changes);
        return save(existing);
    }

    /**
     * Deletes a bridge; its worker is stopped by the next reconciliation.
     *
     * @param id bridge id
     * @throws BridgeNotFoundException if it does not exist
     */
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new BridgeNotFoundException(id);
        }
        repository.deleteById(id);
    }

    /**
     * Enables or disables a bridge. Idempotent: no write happens when the state already matches (RES-10).
     *
     * @param id bridge id
     * @param enabled desired state
     * @return the bridge
     * @throws BridgeNotFoundException if it does not exist
     */
    public BridgeDocument setEnabled(String id, boolean enabled) {
        BridgeDocument bridge = get(id);
        if (bridge.isEnabled() == enabled) {
            return bridge;
        }
        bridge.setEnabled(enabled);
        return save(bridge);
    }

    /** Collapses legacy and duplicate destinations into the {@code destinations} list (MONGO-2). */
    @SuppressWarnings("deprecation")
    private static void normalize(BridgeDocument bridge) {
        List<BridgeDestination> destinations = new ArrayList<>();
        bridge.resolveDestinationTopics().forEach(topic -> destinations.add(new BridgeDestination(topic)));
        bridge.setDestinations(destinations);
        bridge.setDestination(null);
    }

    /** Rules that span several fields; single-field rules are checked by Jakarta Validation (API-5). */
    private static void validate(BridgeDocument bridge) {
        List<String> destinations = bridge.resolveDestinationTopics();
        if (destinations.isEmpty()) {
            throw new BridgeValidationException("destinations", "At least one destination topic is required");
        }
        if (bridge.getSource() == null || bridge.getSource().getTopic() == null) {
            throw new BridgeValidationException("source.topic", "A source topic is required");
        }
        if (destinations.contains(bridge.getSource().getTopic())) {
            throw new BridgeValidationException("destinations", "A destination cannot be the source topic");
        }
        if (bridge.isOrderedEffective() && Objects.equals(SHARED, bridge.getSource().subscriptionTypeOrDefault())) {
            throw new BridgeValidationException("ordered",
                    "Ordered bridges need an Exclusive, Failover or Key_Shared subscription");
        }
    }

    private BridgeDocument save(BridgeDocument bridge) {
        try {
            return repository.save(bridge);
        } catch (OptimisticLockingFailureException e) {
            throw staleVersion();
        } catch (DuplicateKeyException e) {
            throw duplicateName(bridge.getName());
        }
    }

    private static BridgeConflictException staleVersion() {
        return new BridgeConflictException("The bridge was changed by someone else; reload it and try again");
    }

    private static BridgeConflictException duplicateName(String name) {
        return new BridgeConflictException("A bridge named '" + name + "' already exists");
    }
}
