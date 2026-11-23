package com.catacumbas69.catasonarr.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FolderTermTest {

    @Test
    void parse_titleAndTheYearAtTheEnd_asSonarrNamesItsFolders() {
        assertThat(FolderTerm.parse("The Office (2005)")).isEqualTo(new FolderTerm(null, null, "The Office", 2005));
        assertThat(FolderTerm.parse("The Office (US) (2005)").title()).isEqualTo("The Office (US)");
        assertThat(FolderTerm.parse("1923 (2022)")).isEqualTo(new FolderTerm(null, null, "1923", 2022));
        assertThat(FolderTerm.parse("Mr. Robot")).isEqualTo(new FolderTerm(null, null, "Mr. Robot", null));
    }

    @Test
    void parse_anIdInTheNameWins() {
        assertThat(FolderTerm.parse("Doctor Who {tvdb-78804}").tvdbId()).isEqualTo(78804);
        assertThat(FolderTerm.parse("Andor [tvdbid-393189]").tvdbId()).isEqualTo(393189);
        assertThat(FolderTerm.parse("Lost {imdb-tt0411008}").imdbId()).isEqualTo("tt0411008");
        assertThat(FolderTerm.parse("Show {tmdb-123} (2010)")).isEqualTo(new FolderTerm(null, null, "Show", 2010));
    }

    @Test
    void parse_dotsAndUnderscoresAreSpaces_whenTheNameHasNone() {
        assertThat(FolderTerm.parse("The.Expanse").title()).isEqualTo("The Expanse");
        assertThat(FolderTerm.parse("Breaking_Bad (2008)")).isEqualTo(new FolderTerm(null, null, "Breaking Bad", 2008));
    }
}
