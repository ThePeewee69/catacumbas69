package com.catacumbas69.catasonarr.parser;

import com.catacumbas69.catasonarr.quality.Qualities;

/**
 * Whether a title is a scene release's name, translated from Sonarr 4.0.20's {@code SceneChecker.cs} (GPL-3.0):
 * dots and no spaces, and it parses with a group, a known quality and a series' title. It prefers saying no.
 */
public final class SceneChecker {

    private SceneChecker() {
    }

    /**
     * Whether a title is a scene name.
     *
     * @param title the title
     * @return whether it is
     */
    public static boolean isSceneTitle(String title) {
        if (!title.contains(".") || title.contains(" ")) {
            return false;
        }
        ParsedEpisodeInfo parsed = Parser.parseTitle(title);
        return parsed != null && parsed.releaseGroup != null && !parsed.quality.quality.equals(Qualities.UNKNOWN)
                && !ParserText.isBlank(parsed.seriesTitle);
    }
}
