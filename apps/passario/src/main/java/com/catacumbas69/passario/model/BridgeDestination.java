package com.catacumbas69.passario.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One destination of a bridge.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BridgeDestination {

    /** Fully-qualified topic: {@code persistent://tenant/namespace/topic} (PUL-3). */
    private String topic;
}
