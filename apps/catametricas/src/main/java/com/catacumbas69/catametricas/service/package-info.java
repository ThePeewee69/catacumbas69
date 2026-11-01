/**
 * Collection of Pulsar topic statistics, shared over the instances through Pulsar (ADR 0012): publishing one
 * task per namespace and interval ({@link com.catacumbas69.catametricas.service.CollectTaskPublisher}), running
 * the tasks ({@link com.catacumbas69.catametricas.service.CollectTaskConsumer},
 * {@link com.catacumbas69.catametricas.service.StatsCollector}), reading Pulsar ({@link
 * com.catacumbas69.catametricas.service.PulsarStatsReader}), writing MongoDB idempotently ({@link
 * com.catacumbas69.catametricas.service.StatsWriter}); and the queries behind the API ({@link
 * com.catacumbas69.catametricas.service.MetricsQueryService}).
 */
package com.catacumbas69.catametricas.service;
