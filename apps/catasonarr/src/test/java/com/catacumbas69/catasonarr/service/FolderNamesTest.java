package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.model.NamingConfig;
import org.junit.jupiter.api.Test;

class FolderNamesTest {

    private static final FolderNames.Names ANDOR =
            new FolderNames.Names("Star Wars: Andor", 2022, 393189, 60574, 83867, "tt9253284");

    @Test
    void sonarrsDefault_isTheTitle_withTheColonReplacedSmartly() {
        assertThat(FolderNames.seriesFolder(NamingConfig.DEFAULT, ANDOR)).isEqualTo("Star Wars - Andor");
    }

    @Test
    void titleYearAndIds() {
        assertThat(FolderNames.seriesFolder(naming("{Series TitleYear} [tvdbid-{TvdbId}]"), ANDOR))
                .isEqualTo("Star Wars - Andor (2022) [tvdbid-393189]");
        assertThat(FolderNames.seriesFolder(naming("{Series Title} {imdb-{ImdbId}}"), ANDOR))
                .as("Sonarr keeps the braces around a token's value")
                .isEqualTo("Star Wars - Andor {imdb-tt9253284}");
    }

    @Test
    void theArticleMovedToTheEnd_andASeparatorInTheToken() {
        FolderNames.Names office = new FolderNames.Names("The Office (US)", 2005, 73244, 0, 0, null);
        assertThat(FolderNames.seriesFolder(naming("{Series TitleThe}"), office)).isEqualTo("Office, The (US)");
        assertThat(FolderNames.seriesFolder(naming("{Series.Title}"), office)).isEqualTo("The.Office.(US)");
    }

    @Test
    void aLowerCaseToken_givesALowerCaseName() {
        assertThat(FolderNames.seriesFolder(naming("{series title}"), ANDOR)).isEqualTo("star wars - andor");
    }

    @Test
    void illegalCharacters_replacedOrRemoved() {
        FolderNames.Names whatIf = new FolderNames.Names("What If...? / Marvel", 2021, 1, 0, 0, null);
        assertThat(FolderNames.seriesFolder(NamingConfig.DEFAULT, whatIf)).isEqualTo("What If.! + Marvel");
        NamingConfig remove = new NamingConfig("naming", "{Series Title}", "Season {season}", "Specials", false, 4, "");
        assertThat(FolderNames.seriesFolder(remove, ANDOR)).isEqualTo("Star Wars Andor");
    }

    @Test
    void seasonFolders_withPadding_andSpecials() {
        NamingConfig padded = new NamingConfig("naming", "{Series Title}", "Season {season:00}", "Specials", true, 4,
                "");
        assertThat(FolderNames.seasonFolder(padded, ANDOR, 3)).isEqualTo("Season 03");
        assertThat(FolderNames.seasonFolder(padded, ANDOR, 0)).isEqualTo("Specials");
    }

    private static NamingConfig naming(String seriesFolder) {
        return new NamingConfig("naming", seriesFolder, "Season {season}", "Specials", true, 4, "");
    }
}
