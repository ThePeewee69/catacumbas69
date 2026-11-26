package com.catacumbas69.catasonarr.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sonarr's languages and their ids, translated from Sonarr 4.0.20's {@code Language.cs} (GPL-3.0).
 */
public final class Languages {

    /** The unknown language. */
    public static final Language UNKNOWN = new Language(0, "Unknown");

    /** The series' original language, as a custom format or a German DL release says it (Sonarr's -2). */
    public static final Language ORIGINAL = new Language(-2, "Original");

    /** Every language, by Sonarr's id. */
    public static final List<Language> ALL = List.of(UNKNOWN,
            new Language(1, "English"), new Language(2, "French"), new Language(3, "Spanish"),
            new Language(4, "German"), new Language(5, "Italian"), new Language(6, "Danish"),
            new Language(7, "Dutch"), new Language(8, "Japanese"), new Language(9, "Icelandic"),
            new Language(10, "Chinese"), new Language(11, "Russian"), new Language(12, "Polish"),
            new Language(13, "Vietnamese"), new Language(14, "Swedish"), new Language(15, "Norwegian"),
            new Language(16, "Finnish"), new Language(17, "Turkish"), new Language(18, "Portuguese"),
            new Language(19, "Flemish"), new Language(20, "Greek"), new Language(21, "Korean"),
            new Language(22, "Hungarian"), new Language(23, "Hebrew"), new Language(24, "Lithuanian"),
            new Language(25, "Czech"), new Language(26, "Arabic"), new Language(27, "Hindi"),
            new Language(28, "Bulgarian"), new Language(29, "Malayalam"), new Language(30, "Ukrainian"),
            new Language(31, "Slovak"), new Language(32, "Thai"), new Language(33, "Portuguese (Brazil)"),
            new Language(34, "Spanish (Latino)"), new Language(35, "Romanian"), new Language(36, "Latvian"),
            new Language(37, "Persian"), new Language(38, "Catalan"), new Language(39, "Croatian"),
            new Language(40, "Serbian"), new Language(41, "Bosnian"), new Language(42, "Estonian"),
            new Language(43, "Tamil"), new Language(44, "Indonesian"), new Language(45, "Macedonian"),
            new Language(46, "Slovenian"), ORIGINAL);

    private static final Map<String, Language> BY_NAME = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(l -> l.name().toLowerCase(Locale.ROOT), Function.identity()));

    private Languages() {
    }

    /**
     * Finds a language by Sonarr's id.
     *
     * @param id the id
     * @return the language, or {@link #UNKNOWN}
     */
    public static Language byId(int id) {
        return ALL.stream().filter(l -> l.id() == id).findFirst().orElse(UNKNOWN);
    }

    /**
     * Finds a language by its English name, as TVmaze gives it.
     *
     * @param name e.g. {@code English}; may be {@code null}
     * @return the language, or {@link #UNKNOWN}
     */
    public static Language byName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        return BY_NAME.getOrDefault(name.trim().toLowerCase(Locale.ROOT), UNKNOWN);
    }
}
