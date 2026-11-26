package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.model.Language;
import java.util.ArrayList;
import java.util.List;

/** What a subtitle file's name says (Sonarr's {@code SubtitleTitleInfo}). */
public final class SubtitleTitleInfo {

    /** Its tags: forced, sdh, … */
    public List<String> languageTags = new ArrayList<>();
    /** Its language. */
    public Language language;
    /** Its title as written, with a copy number. */
    public String rawTitle;
    /** Its title. */
    public String title;
    /** Its copy number, 0 for none. */
    public int copy;
    /** Whether the title comes before the tags. */
    public boolean titleFirst;
}
