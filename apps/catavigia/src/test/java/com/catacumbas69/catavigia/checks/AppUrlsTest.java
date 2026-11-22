package com.catacumbas69.catavigia.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.TestProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AppUrlsTest {

    @Test
    void all_watchesCatasonarrToo_inStartOrder() {
        AppUrls urls = new AppUrls(TestProperties.properties(), noSettings());

        assertThat(urls.all()).containsExactly(
                Map.entry("cataponte", "http://cataponte:8082"),
                Map.entry("cataudit", "http://cataudit:8084"),
                Map.entry("catametricas", "http://catametricas:8083"),
                Map.entry("catasonarr", "http://catasonarr:8086"),
                Map.entry("catapulsar", "http://catapulsar:8081"));
    }

    @Test
    void pageUrl_onlyWhenSet_andOnlyHttp() {
        AppUrls urls = new AppUrls(TestProperties.properties(), noSettings());

        assertThat(urls.pageUrl("catasonarr")).contains("https://catasonarr.catacumbas69.com");
        assertThat(urls.pageUrl("cataudit")).isEmpty();
        assertThat(urls.pageUrl("cataponte")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SharedSettings> noSettings() {
        return mock(ObjectProvider.class);
    }
}
