package com.catacumbas69.pulsario.dto;

import java.util.Map;

public record MessageDTO(
        String messageId,
        String payload,
        String key,
        long publishTime,
        Map<String, String> properties,
        boolean isBinary
) {}
