package com.catacumbas69.cataponte.dto;

import com.catacumbas69.cataponte.config.CataponteProperties;
import com.catacumbas69.cataponte.model.BridgeDestination;
import com.catacumbas69.cataponte.model.BridgeDocument;
import com.catacumbas69.cataponte.model.BridgeSource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts between API records and {@link BridgeDocument}, applying the documented defaults.
 */
@Component
public class BridgeMapper {

    private static final String ORDERED_DEFAULT_SUBSCRIPTION = "Failover";
    private static final String UNORDERED_DEFAULT_SUBSCRIPTION = "Shared";

    private final int defaultMaxInFlight;

    /**
     * Creates the mapper.
     *
     * @param properties application properties, for the default {@code maxInFlight}
     */
    public BridgeMapper(CataponteProperties properties) {
        this.defaultMaxInFlight = properties.bridge().defaultMaxInFlight();
    }

    /**
     * Builds a bridge definition from a request.
     *
     * @param request validated request
     * @return a new, unsaved document
     */
    public BridgeDocument toDocument(BridgeRequest request) {
        BridgeDocument bridge = new BridgeDocument();
        bridge.setName(request.name().trim());
        bridge.setDescription(request.description());
        bridge.setEnabled(valueOr(request.enabled(), true));
        bridge.setSource(toSource(request));
        bridge.setDestinations(toDestinations(request.destinations()));
        bridge.setPreserveKey(valueOr(request.preserveKey(), true));
        bridge.setPreserveProperties(valueOr(request.preserveProperties(), true));
        bridge.setOrdered(request.ordered());
        bridge.setMaxInFlight(request.maxInFlight());
        return bridge;
    }

    /**
     * Converts a stored bridge to its API representation, resolving effective values.
     *
     * @param bridge stored bridge
     * @return the response record
     */
    public BridgeResponse toResponse(BridgeDocument bridge) {
        BridgeSource source = bridge.getSource();
        return new BridgeResponse(
                bridge.getId(),
                bridge.getName(),
                bridge.getDescription(),
                bridge.isEnabled(),
                source == null ? null : new BridgeResponse.Source(source.getTopic(), source.getSubscription(),
                        source.subscriptionTypeOrDefault()),
                bridge.resolveDestinationTopics().stream().map(BridgeResponse.Destination::new).toList(),
                bridge.isPreserveKey(),
                bridge.isPreserveProperties(),
                bridge.isOrderedEffective(),
                bridge.getMaxInFlight() != null ? bridge.getMaxInFlight() : defaultMaxInFlight,
                bridge.getVersion(),
                bridge.getCreatedAt(),
                bridge.getUpdatedAt());
    }

    private static BridgeSource toSource(BridgeRequest request) {
        BridgeRequest.Source source = request.source();
        String type = source.subscriptionType();
        if (type == null || type.isBlank()) {
            boolean ordered = request.ordered() == null || request.ordered();
            type = ordered ? ORDERED_DEFAULT_SUBSCRIPTION : UNORDERED_DEFAULT_SUBSCRIPTION;
        }
        return new BridgeSource(source.topic().trim(), source.subscription().trim(), type);
    }

    private static List<BridgeDestination> toDestinations(List<BridgeRequest.Destination> destinations) {
        List<BridgeDestination> result = new ArrayList<>();
        destinations.forEach(destination -> result.add(new BridgeDestination(destination.topic().trim())));
        return result;
    }

    private static boolean valueOr(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
