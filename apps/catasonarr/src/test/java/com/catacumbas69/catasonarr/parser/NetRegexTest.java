package com.catacumbas69.catasonarr.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetRegexTest {

    @Test
    void repeatedGroup_keepsEveryCapture_asDotNet() {
        NetMatch m = NetRegex.compile("S(?<season>\\d+)(?:E(?<episode>\\d+))+").match("Show.S01E02E03E04.mkv");

        assertThat(m.group("episode").captures()).extracting(NetCapture::value).containsExactly("02", "03", "04");
        assertThat(m.group("episode").value()).isEqualTo("04");
        assertThat(m.group("season").value()).isEqualTo("01");
    }

    @Test
    void repeatedGroup_splitsAsTheWholeMatchNeeds() {
        NetMatch m = NetRegex.compile("(?:E(?<n>(\\d{3}|\\d{4})))+ x").match("E1135 x");

        assertThat(m.group("n").captures()).extracting(NetCapture::value).containsExactly("1135");
    }

    @Test
    void sharedNames_andUnderscores_areOneGroup() {
        NetRegex r = NetRegex.compile("(?<iso_code>[a-z]{2})-(?<x>\\d)|(?<iso_code>[A-Z]{3})");

        assertThat(r.match("ENG").group("iso_code").value()).isEqualTo("ENG");
        assertThat(r.match("en-1").group("iso_code").value()).isEqualTo("en");
    }

    @Test
    void unboundedLookbehind_isBounded() {
        assertThat(NetRegex.compile("(?<!\\d+)\\d{2}(?!\\d+)").isMatch("a12b")).isTrue();
        assertThat(NetRegex.compile("(?<!\\d+)\\d{2}(?!\\d+)").isMatch("123")).isFalse();
    }

    @Test
    void ignorePatternWhitespace_keepsSpacesInClasses() {
        NetRegex r = NetRegex.compile("a [ ] b  # comment", NetRegex.IGNORE_PATTERN_WHITESPACE);

        assertThat(r.isMatch("a b")).isTrue();
        assertThat(r.isMatch("ab")).isFalse();
    }

    @Test
    void replacement_readsDotNetGroups() {
        assertThat(NetRegex.compile("(?<title>\\w+) (?<year>\\d{4})").replace("Show 2020", "${title} (${year})"))
                .isEqualTo("Show (2020)");
    }

    @Test
    void bracketAndBraceAsCharacters_asDotNet() {
        assertThat(NetRegex.compile("[([]x{tvdb-").isMatch("[x{tvdb-")).isTrue();
    }
}
