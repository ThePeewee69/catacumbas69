package com.catacumbas69.cataponte.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.catacumbas69.cataponte.TestBridges;
import com.catacumbas69.cataponte.model.AreaDocument;
import com.catacumbas69.cataponte.model.BridgeDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AreaFilterTest {

    @Test
    void star_matchesWithinOneSegment() {
        AreaFilter filter = filter("home/media/*");

        assertThat(filter.matches("persistent://home/media/events")).isTrue();
        assertThat(filter.matches("persistent://home/media/events-partition-0")).isTrue();
        assertThat(filter.matches("persistent://home/other/events")).isFalse();
        assertThat(filter.matches("persistent://home/media")).isFalse();
    }

    @Test
    void doubleStar_matchesAcrossSegments() {
        AreaFilter filter = filter("home/**");

        assertThat(filter.matches("persistent://home/media/events")).isTrue();
        assertThat(filter.matches("persistent://office/media/events")).isFalse();
    }

    @Test
    void starInEverySegment_andPrefixes() {
        assertThat(filter("*/*/events").matches("persistent://office/sensors/events")).isTrue();
        assertThat(filter("*/*/events").matches("persistent://office/sensors/events-2")).isFalse();
        assertThat(filter("home/media/events*").matches("persistent://home/media/events-partition-3")).isTrue();
    }

    @Test
    void schemes_areKeptApart() {
        assertThat(filter("home/media/*").matches("non-persistent://home/media/events")).isFalse();
        assertThat(filter("non-persistent://home/media/*").matches("non-persistent://home/media/events")).isTrue();
        assertThat(filter("non-persistent://home/media/*").matches("persistent://home/media/events")).isFalse();
    }

    @Test
    void dotsAreLiteral() {
        assertThat(filter("home/media/a.b").matches("persistent://home/media/axb")).isFalse();
    }

    @Test
    void noPatterns_allowEveryTopic() {
        assertThat(filter().matches("persistent://any/thing/at-all")).isTrue();
        assertThat(AreaFilter.of(null).matches("persistent://any/thing/at-all")).isTrue();
    }

    @Test
    void outside_listsTheSourceAndDestinationsThatDoNotMatch() {
        BridgeDocument bridge = TestBridges.unordered("1", TestBridges.DEST_A, "persistent://office/x/y");

        assertThat(filter("home/media/*").outside(bridge)).containsExactly("persistent://office/x/y");
        assertThat(filter("office/**").outside(bridge))
                .containsExactly(TestBridges.SOURCE, TestBridges.DEST_A);
    }

    @Test
    void normalize_trimsAndDropsBlanksAndDuplicates() {
        List<String> patterns = new ArrayList<>(List.of(" home/media/* ", "", "home/media/*", "home/**"));
        patterns.add(null);

        assertThat(AreaFilter.normalize(patterns)).containsExactly("home/media/*", "home/**");
        assertThat(AreaFilter.normalize(null)).isEmpty();
    }

    @Test
    void normalize_refusesInvalidPatterns() {
        assertThatThrownBy(() -> AreaFilter.normalize(List.of("persistent://home/media/*")))
                .isInstanceOf(BridgeValidationException.class).hasMessageContaining("without persistent://");
        assertThatThrownBy(() -> AreaFilter.normalize(List.of("home/media")))
                .isInstanceOf(BridgeValidationException.class).hasMessageContaining("three segments");
        assertThatThrownBy(() -> AreaFilter.normalize(List.of("home/me dia/*")))
                .isInstanceOf(BridgeValidationException.class);
        assertThatThrownBy(() -> AreaFilter.normalize(List.of("home//x")))
                .isInstanceOf(BridgeValidationException.class);
    }

    @Test
    void normalize_refusesTooManyPatterns() {
        List<String> patterns = new ArrayList<>();
        for (int i = 0; i <= AreaFilter.MAX_PATTERNS; i++) {
            patterns.add("home/media/t" + i);
        }

        assertThatThrownBy(() -> AreaFilter.normalize(patterns))
                .isInstanceOf(BridgeValidationException.class).hasMessageContaining("At most");
    }

    private static AreaFilter filter(String... patterns) {
        AreaDocument area = new AreaDocument();
        area.setPatterns(patterns.length == 0 ? Collections.emptyList() : List.of(patterns));
        return AreaFilter.of(area);
    }
}
