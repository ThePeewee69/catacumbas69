package com.catacumbas69.catacommons.consumers;

import com.catacumbas69.catacommons.pulsar.CatacumbasTopics;
import java.util.Set;
import java.util.TreeSet;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;

/**
 * Finds the apps whose consumers are controlled (orch.md decision 118): those whose namespace
 * {@code catacumbas69/<app>} has a {@code consumers} topic, which catacommons creates when an app turns its consumer
 * control on. Catapulsar's Consumers panel and Catavigia's {@code consumer.paused} read them, so neither names an
 * app in its code and a new app is found on its own.
 */
public final class ConsumerApps {

    private ConsumerApps() {
    }

    /**
     * Lists the apps with a consumers topic.
     *
     * @param admin Pulsar's admin client
     * @return the app names, sorted
     * @throws PulsarAdminException when Pulsar does not answer
     */
    public static Set<String> discover(PulsarAdmin admin) throws PulsarAdminException {
        Set<String> apps = new TreeSet<>();
        for (String namespace : admin.namespaces().getNamespaces(CatacumbasTopics.TENANT)) {
            String app = namespace.substring(namespace.indexOf('/') + 1);
            if (admin.topics().getList(namespace).contains(CatacumbasTopics.consumers(app))) {
                apps.add(app);
            }
        }
        return apps;
    }
}
