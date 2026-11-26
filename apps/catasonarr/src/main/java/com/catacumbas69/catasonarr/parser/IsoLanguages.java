package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Languages;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sonarr's languages by ISO 639 code, translated from Sonarr 4.0.20's {@code IsoLanguages.cs} (GPL-3.0).
 */
public final class IsoLanguages {

    /**
     * A language's codes.
     *
     * @param twoLetterCode ISO 639-1, e.g. {@code pt}
     * @param countryCode a country, e.g. {@code br}, or {@code ""}
     * @param threeLetterCode ISO 639-2/T, e.g. {@code por}
     * @param language Sonarr's language
     */
    public record IsoLanguage(String twoLetterCode, String countryCode, String threeLetterCode, Language language) {
    }

    private static final List<IsoLanguage> ALL = List.of(
            iso("en", "", "eng", "English"),
            iso("fr", "fr", "fra", "French"),
            iso("es", "", "spa", "Spanish"),
            iso("de", "de", "deu", "German"),
            iso("it", "", "ita", "Italian"),
            iso("da", "", "dan", "Danish"),
            iso("nl", "", "nld", "Dutch"),
            iso("ja", "", "jpn", "Japanese"),
            iso("is", "", "isl", "Icelandic"),
            iso("zh", "cn", "zho", "Chinese"),
            iso("ru", "", "rus", "Russian"),
            iso("pl", "", "pol", "Polish"),
            iso("vi", "", "vie", "Vietnamese"),
            iso("sv", "", "swe", "Swedish"),
            iso("no", "", "nor", "Norwegian"),
            iso("nb", "", "nob", "Norwegian"),
            iso("fi", "", "fin", "Finnish"),
            iso("tr", "", "tur", "Turkish"),
            iso("pt", "pt", "por", "Portuguese"),
            iso("nl", "", "nld", "Flemish"),
            iso("el", "", "ell", "Greek"),
            iso("ko", "", "kor", "Korean"),
            iso("hu", "", "hun", "Hungarian"),
            iso("he", "", "heb", "Hebrew"),
            iso("lt", "", "lit", "Lithuanian"),
            iso("cs", "", "ces", "Czech"),
            iso("ar", "", "ara", "Arabic"),
            iso("hi", "", "hin", "Hindi"),
            iso("bg", "", "bul", "Bulgarian"),
            iso("ml", "", "mal", "Malayalam"),
            iso("uk", "", "ukr", "Ukrainian"),
            iso("sk", "", "slk", "Slovak"),
            iso("th", "th", "tha", "Thai"),
            iso("pt", "br", "por", "Portuguese (Brazil)"),
            iso("es", "mx", "spa", "Spanish (Latino)"),
            iso("ro", "", "ron", "Romanian"),
            iso("lv", "", "lav", "Latvian"),
            iso("fa", "", "fas", "Persian"),
            iso("ca", "", "cat", "Catalan"),
            iso("hr", "", "hrv", "Croatian"),
            iso("sr", "", "srp", "Serbian"),
            iso("bs", "", "bos", "Bosnian"),
            iso("et", "", "est", "Estonian"),
            iso("ta", "", "tam", "Tamil"),
            iso("id", "", "ind", "Indonesian"),
            iso("mk", "", "mkd", "Macedonian"),
            iso("sl", "", "slv", "Slovenian"));

    private static final Map<String, String> ALTERNATE = Map.of(
            "cze", "Czech", "dut", "Dutch", "mac", "Macedonian", "rum", "Romanian", "yue", "Chinese",
            "zhtw", "Chinese");

    /** ISO 639-2 bibliographic codes and their terminology codes (Sonarr's {@code Iso639BTMap}). */
    public static final Map<String, String> ISO_639_BT = Map.ofEntries(
            Map.entry("alb", "sqi"),
            Map.entry("arm", "hye"),
            Map.entry("baq", "eus"),
            Map.entry("bur", "mya"),
            Map.entry("chi", "zho"),
            Map.entry("cze", "ces"),
            Map.entry("dut", "nld"),
            Map.entry("fre", "fra"),
            Map.entry("geo", "kat"),
            Map.entry("ger", "deu"),
            Map.entry("gre", "ell"),
            Map.entry("ice", "isl"),
            Map.entry("mac", "mkd"),
            Map.entry("mao", "mri"),
            Map.entry("may", "msa"),
            Map.entry("per", "fas"),
            Map.entry("rum", "ron"),
            Map.entry("slo", "slk"),
            Map.entry("tib", "bod"),
            Map.entry("wel", "cym"));

    private IsoLanguages() {
    }

    private static IsoLanguage iso(String two, String country, String three, String language) {
        return new IsoLanguage(two, country, three, Languages.byName(language));
    }

    /**
     * The language of a code: {@code pt}, {@code pt-br}, {@code por}, {@code ger}, {@code cze}.
     *
     * @param isoCode the code
     * @return the language's codes, or {@code null}
     */
    public static IsoLanguage find(String isoCode) {
        String[] iso = isoCode.split("-");
        String langCode = iso[0].toLowerCase(Locale.ROOT);
        if (langCode.length() == 2) {
            List<IsoLanguage> found = ALL.stream().filter(l -> l.twoLetterCode().equals(langCode)).toList();
            if (iso.length > 1) {
                String country = iso[1].toLowerCase(Locale.ROOT);
                boolean any = found.stream().anyMatch(l -> l.countryCode().equals(country));
                found = found.stream()
                        .filter(l -> any ? l.countryCode().equals(country) : l.countryCode().isEmpty()).toList();
            }
            return found.isEmpty() ? null : found.get(0);
        }
        if (ALTERNATE.containsKey(isoCode)) {
            return get(Languages.byName(ALTERNATE.get(isoCode)));
        }
        if (langCode.length() == 3) {
            String code = ISO_639_BT.getOrDefault(langCode, langCode);
            return ALL.stream().filter(l -> l.threeLetterCode().equals(code)).findFirst().orElse(null);
        }
        return null;
    }

    /**
     * A language's codes.
     *
     * @param language the language
     * @return its codes, or {@code null}
     */
    public static IsoLanguage get(Language language) {
        return ALL.stream().filter(l -> l.language().equals(language)).findFirst().orElse(null);
    }
}
