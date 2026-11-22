package com.catacumbas69.catasonarr.model;

import java.util.List;

/**
 * An item of a quality profile, as Sonarr stores it: either one quality ({@code quality} set) or a group of them
 * ({@code id} from 1000 and {@code name} set, {@code items} the qualities).
 *
 * @param id the group's id, or {@code null} for a single quality
 * @param name the group's name, or {@code null}
 * @param quality Sonarr's quality id, or {@code null} for a group
 * @param items the group's qualities, empty for a single quality
 * @param allowed whether releases of it are accepted
 */
public record ProfileItem(Integer id, String name, Integer quality, List<ProfileItem> items, boolean allowed) {
}
