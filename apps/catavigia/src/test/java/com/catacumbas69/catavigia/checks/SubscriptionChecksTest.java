package com.catacumbas69.catavigia.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.settings.SharedSettings;
import com.catacumbas69.catavigia.checks.SubscriptionsReader.SubscriptionBacklog;
import com.catacumbas69.catavigia.model.BacklogLimit;
import com.catacumbas69.catavigia.service.BacklogLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SubscriptionChecksTest {

    private static final String ORDERS = "persistent://home/media/orders";
    private static final String DLQ = "persistent://home/media/orders-cataponte-x-DLQ";

    private final SubscriptionsReader reader = mock(SubscriptionsReader.class);
    private final BacklogLimitService limits = mock(BacklogLimitService.class);

    @Test
    void reader_sumsThePartitionsOfEachSubscription() throws Exception {
        AppsClient apps = mock(AppsClient.class);
        AppUrls urls = mock(AppUrls.class);
        when(urls.catametricas()).thenReturn("http://catametricas:8083");
        when(apps.get("http://catametricas:8083/api/v1/subscriptions")).thenReturn(new ObjectMapper().readTree("""
                [{"topic":"persistent://home/media/orders-partition-0","baseName":"orders","subscription":"a","backlog":3},
                 {"topic":"persistent://home/media/orders-partition-1","baseName":"orders","subscription":"a","backlog":4},
                 {"topic":"persistent://home/media/orders-partition-1","baseName":"orders","subscription":"b","backlog":1}]
                """));

        List<SubscriptionBacklog> subs = new SubscriptionsReader(apps, urls).read();

        assertThat(subs).extracting(sub -> sub.topic() + "|" + sub.subscription() + "=" + sub.backlog())
                .containsExactly(ORDERS + "|a=7", ORDERS + "|b=1");
    }

    @Test
    void poison_reportsEveryDeadLetterTopicWithMessagesWaiting() throws Exception {
        when(reader.read()).thenReturn(List.of(
                new SubscriptionBacklog(DLQ, "orders-cataponte-x-DLQ", "dlq", 2),
                new SubscriptionBacklog(DLQ.replace("-x-", "-y-"), "orders-cataponte-y-DLQ", "dlq", 0),
                new SubscriptionBacklog(ORDERS, "orders", "dlq", 9)));

        assertThat(new PoisonCheck(reader).run()).extracting(Finding::subject).containsExactly(DLQ);
    }

    @Test
    void backlog_usesTheSubscriptionsOwnLimitOrTheGlobalOne_andLeavesOutDlqAndPulsarsOwn() throws Exception {
        when(reader.read()).thenReturn(List.of(
                new SubscriptionBacklog(ORDERS, "orders", "fast", 600),
                new SubscriptionBacklog(ORDERS, "orders", "slow", 600),
                new SubscriptionBacklog(ORDERS, "orders", "__compaction", 99_999),
                new SubscriptionBacklog(DLQ, "orders-cataponte-x-DLQ", "dlq", 99_999)));
        when(limits.byId()).thenReturn(Map.of(BacklogLimit.idOf(ORDERS, "slow"),
                new BacklogLimit(BacklogLimit.idOf(ORDERS, "slow"), ORDERS, "slow", 1_000, "me", null)));

        List<Finding> findings = new BacklogCheck(reader, limits, settings("500")).run();

        assertThat(findings).extracting(Finding::subject).containsExactly(ORDERS + "|fast");
        assertThat(findings.get(0).details()).containsEntry("limit", "500").containsEntry("limitSource", "global");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SharedSettings> settings(String backlogLimit) {
        SharedSettings settings = mock(SharedSettings.class);
        when(settings.get("alerts.backlog-limit")).thenReturn(Optional.of(backlogLimit));
        ObjectProvider<SharedSettings> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(settings);
        return provider;
    }
}
