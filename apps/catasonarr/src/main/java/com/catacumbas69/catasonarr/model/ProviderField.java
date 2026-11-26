package com.catacumbas69.catasonarr.model;

/**
 * One setting of an indexer or a download client, as Sonarr's API sends them: {@code {name, value}}.
 *
 * @param name e.g. {@code baseUrl}
 * @param value its value: text, a number, a boolean or a list
 */
public record ProviderField(String name, Object value) {
}
