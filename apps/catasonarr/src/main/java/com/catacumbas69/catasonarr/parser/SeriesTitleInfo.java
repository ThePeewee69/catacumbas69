package com.catacumbas69.catasonarr.parser;

/**
 * The series' title as a release writes it (Sonarr's {@code SeriesTitleInfo}): with and without its year, and every
 * title when it gives several ({@code A (B)}, {@code A | B}, {@code A AKA B}).
 */
public final class SeriesTitleInfo {

    /** The title as written. */
    public String title;
    /** The title without a year at its end. */
    public String titleWithoutYear;
    /** The year, or 0. */
    public int year;
    /** Every title, or {@code null} when there is one. */
    public String[] allTitles;
}
