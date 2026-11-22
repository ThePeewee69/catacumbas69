package com.catacumbas69.catasonarr.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * How series and season folders are named, as in Sonarr's Settings → Media Management, so Catasonarr names folders
 * as the Sonarr it replaces does (orch.md decision 112). Imported from Sonarr's database; Sonarr's defaults until then.
 *
 * @param id always {@link #ID}
 * @param seriesFolderFormat e.g. {@code {Series Title}} or {@code {Series TitleYear} [tvdbid-{TvdbId}]}
 * @param seasonFolderFormat e.g. {@code Season {season}}
 * @param specialsFolderFormat e.g. {@code Specials}
 * @param replaceIllegalCharacters replace characters a file name cannot have instead of removing them
 * @param colonReplacementFormat 0 delete, 1 dash, 2 space dash, 3 space dash space, 4 smart, 5 custom
 * @param customColonReplacementFormat the replacement when the format is 5
 */
@Document("catasonarr_config")
public record NamingConfig(
        @Id String id,
        String seriesFolderFormat,
        String seasonFolderFormat,
        String specialsFolderFormat,
        boolean replaceIllegalCharacters,
        int colonReplacementFormat,
        String customColonReplacementFormat) {

    /** The document's id in {@code catasonarr_config}. */
    public static final String ID = "naming";

    /** Sonarr's defaults. */
    public static final NamingConfig DEFAULT =
            new NamingConfig(ID, "{Series Title}", "Season {season}", "Specials", true, 4, "");
}
