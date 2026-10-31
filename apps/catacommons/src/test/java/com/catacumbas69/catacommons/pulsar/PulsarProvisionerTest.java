package com.catacumbas69.catacommons.pulsar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.catacumbas69.catacommons.CatacommonsProperties;
import com.catacumbas69.catacommons.events.CloudEvent;
import com.catacumbas69.catacommons.settings.SettingValue;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.pulsar.client.admin.Clusters;
import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.Schemas;
import org.apache.pulsar.client.admin.Tenants;
import org.apache.pulsar.client.admin.TopicPolicies;
import org.apache.pulsar.client.admin.Topics;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

class PulsarProvisionerTest {

    private static final String EVENTS = "persistent://catacumbas69/catapulsar/events";
    private static final String SETTINGS = "persistent://catacumbas69/catapulsar/settings";

    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Tenants tenants = mock(Tenants.class);
    private final Clusters clusters = mock(Clusters.class);
    private final Namespaces namespaces = mock(Namespaces.class);
    private final Topics topics = mock(Topics.class);
    private final TopicPolicies topicPolicies = mock(TopicPolicies.class);
    private final Schemas schemas = mock(Schemas.class);
    private final PulsarProvisioner provisioner = new PulsarProvisioner(admin, properties(), List.of(
            TopicSpec.of("events", Schema.JSON(CloudEvent.class)),
            TopicSpec.compacted("settings", Schema.JSON(SettingValue.class))),
            mock(ScheduledExecutorService.class));

    PulsarProvisionerTest() {
        when(admin.tenants()).thenReturn(tenants);
        when(admin.clusters()).thenReturn(clusters);
        when(admin.namespaces()).thenReturn(namespaces);
        when(admin.topics()).thenReturn(topics);
        when(admin.topicPolicies()).thenReturn(topicPolicies);
        when(admin.schemas()).thenReturn(schemas);
    }

    @Test
    void provision_createsTenantNamespaceTopicsAndPolicies() throws PulsarAdminException {
        when(tenants.getTenants()).thenReturn(List.of("public"));
        when(clusters.getClusters()).thenReturn(List.of("standalone"));

        provisioner.provision();

        ArgumentCaptor<TenantInfo> tenant = ArgumentCaptor.forClass(TenantInfo.class);
        verify(tenants).createTenant(eq("catacumbas69"), tenant.capture());
        assertThat(tenant.getValue().getAllowedClusters()).isEqualTo(Set.of("standalone"));
        verify(namespaces).createNamespace("catacumbas69/catapulsar");
        ArgumentCaptor<RetentionPolicies> retention = ArgumentCaptor.forClass(RetentionPolicies.class);
        verify(namespaces).setRetention(eq("catacumbas69/catapulsar"), retention.capture());
        assertThat(retention.getValue().getRetentionTimeInMinutes()).isEqualTo(7 * 24 * 60);
        assertThat(retention.getValue().getRetentionSizeInMB()).isEqualTo(1024);
        ArgumentCaptor<BacklogQuota> quota = ArgumentCaptor.forClass(BacklogQuota.class);
        verify(namespaces).setBacklogQuota(eq("catacumbas69/catapulsar"), quota.capture());
        assertThat(quota.getValue().getPolicy()).isEqualTo(BacklogQuota.RetentionPolicy.producer_exception);
        verify(topics).createNonPartitionedTopic(EVENTS);
        verify(topics).createNonPartitionedTopic(SETTINGS);
        verify(topicPolicies, never()).setRetention(eq(EVENTS), any());
        verify(topicPolicies).setRetention(eq(SETTINGS), any(RetentionPolicies.class));
        verify(topicPolicies).setCompactionThreshold(SETTINGS, DataSize.ofMegabytes(1).toBytes());
        verify(schemas).createSchema(eq(EVENTS), any(org.apache.pulsar.common.schema.SchemaInfo.class));
    }

    @Test
    void provision_treatsAlreadyExistingAsSuccess() throws PulsarAdminException {
        when(tenants.getTenants()).thenReturn(List.of("catacumbas69"));
        doThrow(mock(PulsarAdminException.ConflictException.class))
                .when(namespaces).createNamespace("catacumbas69/catapulsar");
        doThrow(mock(PulsarAdminException.ConflictException.class)).when(topics).createNonPartitionedTopic(EVENTS);

        provisioner.provision();

        verify(tenants, never()).createTenant(any(), any());
        verify(namespaces).setRetention(eq("catacumbas69/catapulsar"), any(RetentionPolicies.class));
        verify(topics).createNonPartitionedTopic(SETTINGS);
    }

    private static CatacommonsProperties properties() {
        return new CatacommonsProperties("catapulsar",
                new CatacommonsProperties.Events(true, List.of(Duration.ofSeconds(1)), Duration.ofSeconds(10)),
                new CatacommonsProperties.Provisioning(true, Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofDays(7), DataSize.ofGigabytes(1), DataSize.ofGigabytes(1),
                        DataSize.ofMegabytes(1)));
    }
}
