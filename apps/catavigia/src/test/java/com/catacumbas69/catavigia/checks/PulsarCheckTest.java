package com.catacumbas69.catavigia.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.catacumbas69.catavigia.TestProperties;
import java.util.List;
import org.apache.pulsar.client.admin.Brokers;
import org.apache.pulsar.client.admin.Clusters;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.naming.TopicVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PulsarCheckTest {

    private final PulsarAdmin admin = mock(PulsarAdmin.class);
    private final Clusters clusters = mock(Clusters.class);
    private final Brokers brokers = mock(Brokers.class);
    private final PulsarCheck check = new PulsarCheck(admin, TestProperties.properties());

    @BeforeEach
    void wire() throws Exception {
        when(admin.clusters()).thenReturn(clusters);
        when(admin.brokers()).thenReturn(brokers);
        when(clusters.getClusters()).thenReturn(List.of("standalone"));
    }

    @Test
    void nothing_whenTheBrokersAreThereAndTheHealthCheckPasses() throws Exception {
        when(brokers.getActiveBrokers("standalone")).thenReturn(List.of("localhost:8080"));

        assertThat(check.run()).isEmpty();
    }

    @Test
    void brokerDown_whenFewerBrokersThanExpected() throws Exception {
        when(brokers.getActiveBrokers("standalone")).thenReturn(List.of());

        assertThat(check.run()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo("pulsar.broker.down");
            assertThat(finding.details()).containsEntry("active", "0").containsEntry("expected", "1");
        });
    }

    @Test
    void bookieDown_whenBrokersAnswerButTheHealthCheckFails() throws Exception {
        when(brokers.getActiveBrokers("standalone")).thenReturn(List.of("localhost:8080"));
        doThrow(new PulsarAdminException("Not enough non-faulty bookies available"))
                .when(brokers).healthcheck(TopicVersion.V2);

        assertThat(check.run()).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo("pulsar.bookie.down");
            assertThat(finding.subject()).isEqualTo("healthcheck");
        });
    }

    @Test
    void cannotRun_whenTheAdminApiDoesNotAnswer() throws Exception {
        when(clusters.getClusters()).thenThrow(new PulsarAdminException("Connection refused"));

        assertThatThrownBy(check::run).isInstanceOf(SourceUnavailableException.class);
    }
}
