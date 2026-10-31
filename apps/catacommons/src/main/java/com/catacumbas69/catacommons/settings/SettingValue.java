package com.catacumbas69.catacommons.settings;

/**
 * Value of one shared setting on the compacted topic {@code catacumbas69/catapulsar/settings} (ADR 0011).
 * The message key is the setting key (e.g. {@code catametricas.collect-interval-seconds}); an empty
 * message (tombstone) means "use the default".
 *
 * <p>The value is text so one schema fits every setting; numbers are written in decimal.
 *
 * @param value the setting's value
 */
public record SettingValue(String value) {
}
