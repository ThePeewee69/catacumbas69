package com.catacumbas69.catasonarr.parser;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The bits of Sonarr's string helpers the parser needs (GPL-3.0): .NET's number parsing, which allows spaces and a
 * sign, accents removed, and case-insensitive contains.
 */
public final class ParserText {

    private ParserText() {
    }

    /**
     * {@code int.TryParse}: an integer, with optional spaces around and a sign; {@code null} when it is not one.
     *
     * @param value the text
     * @return the number, or {@code null}
     */
    public static Integer tryParseInt(String value) {
        if (value == null) {
            return null;
        }
        String t = value.strip();
        if (t.startsWith("+")) {
            t = t.substring(1);
        }
        if (t.isEmpty() || !t.matches("-?\\d+")) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code long.TryParse}.
     *
     * @param value the text
     * @return whether it is a long integer
     */
    public static boolean isLong(String value) {
        if (value == null) {
            return false;
        }
        String t = value.strip();
        try {
            Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * {@code Convert.ToInt32}: like {@link #tryParseInt}, but fails on what is not a number; empty is an error too.
     *
     * @param value the text
     * @return the number
     */
    public static int toInt(String value) {
        Integer n = tryParseInt(value);
        if (n == null) {
            throw new NumberFormatException(value + " isn't a number");
        }
        return n;
    }

    /**
     * Removes accents (Sonarr's {@code RemoveAccent}): decomposes, drops the marks, composes again.
     *
     * @param text the text
     * @return the text without accents
     */
    public static String removeAccent(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder();
        decomposed.codePoints().filter(c -> Character.getType(c) != Character.NON_SPACING_MARK)
                .forEach(out::appendCodePoint);
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }

    /**
     * Whether a text contains another, without case.
     *
     * @param text the text
     * @param part what to look for
     * @return whether it is there
     */
    public static boolean containsIgnoreCase(String text, String part) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code string.IsNullOrWhiteSpace}.
     *
     * @param text the text
     * @return whether it is null, empty or only spaces
     */
    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * A file name's extension with its dot ({@code Path.GetExtension}), or {@code ""}.
     *
     * @param name the name or path
     * @return e.g. {@code .mkv}
     */
    public static String extension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    /**
     * A file name without folder and extension ({@code Path.GetFileNameWithoutExtension}).
     *
     * @param name the name or path
     * @return the name
     */
    public static String nameWithoutExtension(String name) {
        String file = fileName(name);
        int dot = file.lastIndexOf('.');
        return dot > 0 || dot == 0 && file.length() > 1 ? file.substring(0, dot) : file;
    }

    /**
     * A path's last part ({@code Path.GetFileName}).
     *
     * @param path the path
     * @return the name
     */
    public static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }
}
