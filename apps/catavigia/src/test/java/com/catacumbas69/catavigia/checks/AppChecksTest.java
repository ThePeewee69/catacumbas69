package com.catacumbas69.catavigia.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.consumers.ConsumerStatus;
import com.catacumbas69.catavigia.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AppChecksTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AppsClient apps = mock(AppsClient.class);
    private final AppUrls urls = mock(AppUrls.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void services_reportsAnAppWhoseReadinessIsNotUp_withTheComponentsDown() throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("cataponte", "http://cataponte:8082");
        all.put("catapulsar", "http://catapulsar:8081");
        all.put("cataudit", "http://cataudit:8084");
        when(urls.all()).thenReturn(all);
        when(apps.getAnyStatus("http://cataponte:8082/actuator/health/readiness"))
                .thenReturn(JSON.readTree("{\"status\":\"UP\"}"));
        when(apps.getAnyStatus("http://catapulsar:8081/actuator/health/readiness"))
                .thenReturn(JSON.readTree("{\"status\":\"OUT_OF_SERVICE\"}"));
        when(apps.getAnyStatus("http://catapulsar:8081/actuator/health")).thenReturn(JSON.readTree(
                "{\"status\":\"DOWN\",\"components\":{\"mongo\":{\"status\":\"DOWN\"},\"ping\":{\"status\":\"UP\"}}}"));
        when(apps.getAnyStatus("http://cataudit:8084/actuator/health/readiness"))
                .thenThrow(new SourceUnavailableException("connection refused", null));

        List<Finding> findings = new ServicesCheck(apps, urls).run();

        assertThat(findings).extracting(Finding::subject).containsExactly("catapulsar", "cataudit");
        assertThat(findings.get(0).details()).containsEntry("readiness", "OUT_OF_SERVICE")
                .containsEntry("components", "mongo=DOWN");
        assertThat(findings.get(1).details()).containsEntry("error", "connection refused");
    }

    @Test
    void bridges_reportsFailedAndBlockedBridges() throws Exception {
        when(urls.cataponte()).thenReturn("http://cataponte:8082");
        when(apps.get(anyString())).thenReturn(JSON.readTree("""
                {"activeCount":1,"workers":[
                  {"name":"ok","state":"RUNNING"},
                  {"name":"media-copy","state":"FAILED","problem":"STUCK","stuckMessageId":"1:2:-1"},
                  {"name":"loop","state":"BLOCKED","lastError":"loop"}]}
                """));

        List<Finding> findings = new BridgesCheck(apps, urls).run();

        assertThat(findings).extracting(finding -> finding.type() + " " + finding.subject())
                .containsExactly("bridge.failed media-copy", "bridge.blocked loop");
        assertThat(findings.get(0).details()).containsEntry("problem", "STUCK").containsEntry("stuckMessageId",
                "1:2:-1");
    }

    @Test
    void collection_isStale_afterTheIntervalsWithoutASample() throws Exception {
        when(urls.catametricas()).thenReturn("http://catametricas:8083");
        CollectionCheck check = new CollectionCheck(apps, urls, noSettings(), clock);

        when(apps.get(anyString())).thenReturn(JSON.readTree(
                "{\"lastCollectionAt\":\"2026-11-07T08:57:30Z\",\"intervalSeconds\":60}"));
        assertThat(check.run()).isEmpty();

        when(apps.get(anyString())).thenReturn(JSON.readTree(
                "{\"lastCollectionAt\":\"2026-11-07T08:56:30Z\",\"intervalSeconds\":60}"));
        assertThat(check.run()).singleElement().extracting(Finding::subject).isEqualTo("catametricas");
    }

    @Test
    void collection_needsCatametricas() throws Exception {
        when(urls.catametricas()).thenReturn("http://catametricas:8083");
        when(apps.get(anyString())).thenThrow(new SourceUnavailableException("down", null));

        assertThatThrownBy(() -> new CollectionCheck(apps, urls, noSettings(), clock).run())
                .isInstanceOf(SourceUnavailableException.class);
    }

    @Test
    void consumers_oneAlertPerConsumer_namingThePausedReplicas_andIgnoringGoneOnes() throws Exception {
        ConsumerStatusViews views = mock(ConsumerStatusViews.class);
        when(views.all()).thenReturn(List.of(
                status("cataudit", "a", "PAUSED_AUTO", "MongoDB unavailable", null, NOW),
                status("cataudit", "b", "RUNNING", null, null, NOW),
                status("catametricas", "c", "PAUSED_MANUAL", "paused by hand", "10.0.0.5", NOW.minusSeconds(60)),
                status("catametricas", "d", "RUNNING", null, null, NOW)));

        List<Finding> findings = new ConsumersCheck(views, TestProperties.properties(), clock).run();

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.subject()).isEqualTo("cataudit/audit");
            assertThat(finding.details()).containsEntry("paused", "a (automatic: MongoDB unavailable)")
                    .containsEntry("replicas", "2");
        });
    }

    private static ConsumerStatus status(String app, String instance, String state, String reason, String by,
                                         Instant updated) {
        return new ConsumerStatus(app, instance, "audit", "persistent://catacumbas69/" + app + "/audit", app, state,
                reason, by, updated.toString(), updated.toString(), null, 10, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> noSettings() {
        return mock(ObjectProvider.class);
    }
}
