package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitlesTest {

    @Test
    void clean_asSonarrMatchesReleaseNames() {
        assertThat(Titles.clean("The Lord of the Rings: The Rings of Power")).isEqualTo("thelordringsringspower");
        assertThat(Titles.clean("Pokémon")).isEqualTo("pokemon");
        assertThat(Titles.clean("1923")).isEqualTo("1923");
    }

    @Test
    void sortAndSlug() {
        assertThat(Titles.sort("The Office")).isEqualTo("office");
        assertThat(Titles.slug("Star Wars: Andor (2022)")).isEqualTo("star-wars-andor-2022");
    }
}
