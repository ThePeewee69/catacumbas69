package com.catacumbas69.catasonarr.dto;

import com.catacumbas69.catasonarr.model.Language;
import java.util.List;

/**
 * Sonarr's {@code LanguageProfileResource}. Sonarr v4 has no language profiles and answers one, "Deprecated"; so does
 * Catasonarr, for clients that still ask.
 *
 * @param id 1
 * @param name {@code Deprecated}
 * @param upgradeAllowed {@code false}
 * @param cutoff English
 * @param languages every language, allowed
 */
public record LanguageProfileResource(int id, String name, boolean upgradeAllowed, Language cutoff,
                                      List<Item> languages) {

    /**
     * Sonarr's {@code LanguageProfileItemResource}.
     *
     * @param id 0
     * @param language the language
     * @param allowed {@code true}
     */
    public record Item(int id, Language language, boolean allowed) {
    }
}
