package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.catacumbas69.catasonarr.dto.ConnectionsResponse;
import org.junit.jupiter.api.Test;

class ConnectionServiceTest {

    private static final String URI = "mongodb://catacumbas69-apps:s3cr3t@catacumbas69-svc.mongodb.svc.cluster.local:27017"
            + "/catacumbas69?authSource=admin&replicaSet=catacumbas69";

    @Test
    void mask_hidesThePassword_andNothingElse() {
        assertThat(ConnectionService.mask(URI)).isEqualTo("mongodb://catacumbas69-apps:****@catacumbas69-svc.mongodb"
                + ".svc.cluster.local:27017/catacumbas69?authSource=admin&replicaSet=catacumbas69");
        assertThat(ConnectionService.mask("mongodb+srv://u:p%40ss@cluster.example.com/db"))
                .isEqualTo("mongodb+srv://u:****@cluster.example.com/db");
        assertThat(ConnectionService.mask("mongodb://host:27017/db?x=a@b")).isEqualTo("mongodb://host:27017/db?x=a@b");
        assertThat(ConnectionService.mask("mongodb://host:27017?x=a@b")).isEqualTo("mongodb://host:27017?x=a@b");
        assertThat(ConnectionService.mask("mongodb://user@host/db")).isEqualTo("mongodb://user@host/db");
    }

    @Test
    void describe_whatTheConnectionStringSays_neverThePassword() {
        ConnectionsResponse.Mongo mongo = ConnectionService.describe(URI, "catacumbas69", "meloes:32017");

        assertThat(mongo.hosts()).containsExactly("catacumbas69-svc.mongodb.svc.cluster.local:27017");
        assertThat(mongo.username()).isEqualTo("catacumbas69-apps");
        assertThat(mongo.authSource()).isEqualTo("admin");
        assertThat(mongo.replicaSet()).isEqualTo("catacumbas69");
        assertThat(mongo.database()).isEqualTo("catacumbas69");
        assertThat(mongo.tls()).isFalse();
        assertThat(mongo.outsideUri()).isEqualTo(
                "mongodb://catacumbas69-apps:****@meloes:32017/catacumbas69?directConnection=true&authSource=admin");
        assertThat(mongo.toString()).doesNotContain("s3cr3t");
    }
}
