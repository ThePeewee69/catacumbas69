package com.catacumbas69.catasonarr.metadata;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a series folder's name says, for the library import (orch.md decision 115): an id in it wins
 * ({@code {tvdb-79168}}, {@code [tvdbid-79168]}, {@code {imdb-tt0386676}}), otherwise the title and, at its end, the
 * year in brackets ({@code The Office (2005)}), as Sonarr names its folders. Dots and underscores count as spaces
 * when the name has no spaces.
 *
 * @param tvdbId TheTVDB's id in the name, or {@code null}
 * @param imdbId IMDb's id in the name, or {@code null}
 * @param title the title to search for, without year or ids
 * @param year the year in brackets at the end, or {@code null}
 */
public record FolderTerm(Integer tvdbId, String imdbId, String title, Integer year) {

    private static final Pattern TVDB = Pattern.compile("[\\[{]tvdb(?:id)?-(\\d+)[\\]}]", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMDB = Pattern.compile("[\\[{]imdb(?:id)?-(tt\\d+)[\\]}]", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDS = Pattern.compile("[\\[{](?:tvdb|tmdb|imdb|tvmaze)(?:id)?-[^\\]}]*[\\]}]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("^(.*?)[\\s._-]*\\((\\d{4})\\)$");

    /**
     * Reads a folder's name.
     *
     * @param name the folder's name, or what was typed
     * @return what it says
     */
    public static FolderTerm parse(String name) {
        String text = name == null ? "" : name.strip();
        Matcher tvdb = TVDB.matcher(text);
        Integer tvdbId = tvdb.find() ? Integer.valueOf(tvdb.group(1)) : null;
        Matcher imdb = IMDB.matcher(text);
        String imdbId = imdb.find() ? imdb.group(1).toLowerCase(Locale.ROOT) : null;
        String title = IDS.matcher(text).replaceAll("").strip();
        Integer year = null;
        Matcher y = YEAR.matcher(title);
        if (y.matches()) {
            title = y.group(1).strip();
            year = Integer.valueOf(y.group(2));
        }
        if (!title.contains(" ")) {
            title = title.replace('.', ' ').replace('_', ' ').strip();
        }
        return new FolderTerm(tvdbId, imdbId, title, year);
    }
}
