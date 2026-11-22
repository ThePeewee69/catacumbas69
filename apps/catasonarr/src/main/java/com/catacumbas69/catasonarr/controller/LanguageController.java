package com.catacumbas69.catasonarr.controller;

import com.catacumbas69.catasonarr.dto.LanguageProfileResource;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Languages;
import com.catacumbas69.catasonarr.service.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonarr's {@code /api/v3/language} and its deprecated {@code /api/v3/languageprofile}: Sonarr v4 answers one
 * profile, "Deprecated", for clients written for Sonarr v3; so does Catasonarr.
 */
@Tag(name = "Languages (Sonarr API v3)")
@RestController
public class LanguageController {

    private static final LanguageProfileResource DEPRECATED = new LanguageProfileResource(1, "Deprecated", false,
            new Language(1, "English"),
            Languages.ALL.stream().map(l -> new LanguageProfileResource.Item(0, l, true)).toList());

    /**
     * Every language.
     *
     * @return the languages
     */
    @Operation(summary = "Every language")
    @GetMapping("/api/v3/language")
    public List<Language> languages() {
        return Languages.ALL;
    }

    /**
     * One language.
     *
     * @param id Sonarr's id
     * @return the language
     */
    @Operation(summary = "One language")
    @GetMapping("/api/v3/language/{id}")
    public Language language(@PathVariable int id) {
        return Languages.ALL.stream().filter(l -> l.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("Language " + id));
    }

    /**
     * The language profiles: Sonarr v4's one "Deprecated" profile.
     *
     * @return one profile
     */
    @Operation(summary = "Language profiles (deprecated in Sonarr v4)")
    @GetMapping("/api/v3/languageprofile")
    public List<LanguageProfileResource> profiles() {
        return List.of(DEPRECATED);
    }

    /**
     * One language profile.
     *
     * @param id 1
     * @return the profile
     */
    @Operation(summary = "One language profile (deprecated in Sonarr v4)")
    @GetMapping("/api/v3/languageprofile/{id}")
    public LanguageProfileResource profile(@PathVariable int id) {
        if (id != 1) {
            throw new NotFoundException("Language profile " + id);
        }
        return DEPRECATED;
    }
}
