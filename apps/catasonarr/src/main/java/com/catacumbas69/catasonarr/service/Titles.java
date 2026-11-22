package com.catacumbas69.catasonarr.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sonarr's derived titles: the clean title (to match release names), the sort title and the title slug.
 */
public final class Titles {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NORMALIZE =
            Pattern.compile("((?:\\b|_)(?<!^)(a(?!$)|an|the|and|or|of)(?!$)(?:\\b|_))|\\W|_", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTICLE = Pattern.compile("^(the|an|a)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_SLUG = Pattern.compile("[^a-z0-9]+");

    private Titles() {
    }

    /**
     * Sonarr's {@code CleanSeriesTitle}: lower case, without accents, punctuation and the small words (not at the
     * start), e.g. {@code The Lord of the Rings} → {@code thelordrings}.
     *
     * @param title the title
     * @return the clean title
     */
    public static String clean(String title) {
        if (title == null) {
            return "";
        }
        String plain = removeDiacritics(title);
        String t = plain.chars().allMatch(Character::isDigit) ? plain : NORMALIZE.matcher(plain).replaceAll("");
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * The title for sorting: lower case, without a leading article.
     *
     * @param title the title
     * @return the sort title
     */
    public static String sort(String title) {
        if (title == null) {
            return "";
        }
        return ARTICLE.matcher(removeDiacritics(title).toLowerCase(Locale.ROOT).strip()).replaceFirst("");
    }

    /**
     * The title in an address: lower case, words joined by {@code -}.
     *
     * @param title the title
     * @return the slug, e.g. {@code star-wars-andor}
     */
    public static String slug(String title) {
        if (title == null) {
            return "";
        }
        String s = NOT_SLUG.matcher(removeDiacritics(title).toLowerCase(Locale.ROOT)).replaceAll("-");
        return s.replaceAll("^-+|-+$", "");
    }

    private static String removeDiacritics(String s) {
        return DIACRITICS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
    }
}
