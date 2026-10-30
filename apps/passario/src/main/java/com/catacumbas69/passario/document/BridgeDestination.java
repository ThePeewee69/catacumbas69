package com.catacumbas69.passario.document;

import lombok.Data;

@Data
public class BridgeDestination {
    /** Fully-qualified topic: persistent://tenant/namespace/topic */
    private String topic;
}
