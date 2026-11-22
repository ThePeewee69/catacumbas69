package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityProfile;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class QualityServiceTest {

    @Test
    void defaultProfiles_areSonarrsSix() {
        assertThat(QualityService.defaultProfiles()).extracting(QualityProfile::name)
                .containsExactly("Any", "SD", "HD-720p", "HD-1080p", "Ultra-HD", "HD - 720p/1080p");
    }

    @Test
    void hd1080p_groupsTheWebQualities_andItsCutoffIsHdtv1080p() {
        QualityProfile hd = QualityService.defaultProfiles().get(3);
        assertThat(hd.cutoff()).isEqualTo(9);
        ProfileItem web1080 = hd.items().stream().filter(i -> "WEB 1080p".equals(i.name())).findFirst().orElseThrow();
        assertThat(web1080.id()).as("WEB 480p 1000, WEB 720p 1001, WEB 1080p 1002").isEqualTo(1002);
        assertThat(web1080.allowed()).isTrue();
        assertThat(web1080.items()).extracting(ProfileItem::quality).containsExactly(15, 3);
        assertThat(allowed(hd.items())).containsExactlyInAnyOrder(9, 15, 3, 7);
    }

    @Test
    void sd_cutoffInAGroup_becomesTheGroupsId() {
        QualityProfile any = QualityService.defaultProfiles().get(0);
        assertThat(any.cutoff()).as("SDTV is alone").isEqualTo(1);
        assertThat(QualityService.weight(3)).isEqualTo(QualityService.weight(15));
        assertThat(QualityService.weight(0)).isLessThan(QualityService.weight(21));
    }

    private static List<Integer> allowed(List<ProfileItem> items) {
        return items.stream().filter(ProfileItem::allowed)
                .flatMap(i -> i.quality() != null ? Stream.of(i.quality())
                        : i.items().stream().map(ProfileItem::quality))
                .toList();
    }
}
