# 13. Catametricas serves long-term history from MongoDB and, later, short-term from Prometheus

Date: 2026-10-31 · Status: Accepted (MongoDB source implemented in phase 4, 2026-11-01; Prometheus source later)

## Context

MongoDB holds per-minute history (30 days), per-hour history and all-time totals per topic. Pulsar also
exposes OpenMetrics that a Prometheus can scrape at second resolution. MicroShift has no Prometheus yet;
one is planned together with OpenMetrics collection and Loki.

## Decision

- **Catametricas is the only metrics API**. Callers never query MongoDB or Prometheus themselves.
- **Boundary (2026-11-04):** everything Catapulsar shows in the **view of one topic** (Overview, Subscriptions and
  Backlog tabs: rates, backlog, storage, subscriptions, consumers, peeked messages) is the **current state of that
  topic**, read by Catapulsar **directly from Pulsar** on every refresh, never from Catametricas. That view is for
  operating on the topic (produce, peek, skip, clear backlog), so it must show the state the action applies to.
  Everything **aggregated or historical** (cluster, tenant and namespace overviews, series, all-time totals,
  topics that are gone, alerts) comes from Catametricas.
- Today all ranges come from MongoDB.
- When Prometheus is installed on MicroShift and scrapes the brokers, Catametricas gains a Prometheus source
  (configured by `CATAMETRICAS_PROMETHEUS_URL`): ranges up to 24 h are answered with PromQL over
  `pulsar_in_messages_total`, `pulsar_out_messages_total` and `pulsar_msg_backlog`; longer ranges and
  all-time totals still come from MongoDB. Without the variable, behaviour is unchanged.

## Consequences

- The UI gets second-level resolution for recent data without Catametricas collecting more often.
- Two sources can disagree slightly at the 24 h boundary (different sampling); the API reports which source
  answered.
- MongoDB history stays the long-term record and keeps working when Prometheus is down.

## Implementation (phase 4, 2026-11-01)

- `GET /api/v1/topics?tenant=&namespace=`: every topic or partition of the scope with its current state (from
  the snapshot; absent once the topic is gone) and its all-time traffic.
- `GET /api/v1/series?tenant=&namespace=&groupBy=tenant|namespace|topic&from=&to=&step=`: traffic in and out
  (messages and bytes) and backlog per bucket, summed per group by MongoDB. Steps below an hour (`1m`–`59m`)
  read the per-minute history, whole hours (`1h`, `6h`) the hourly one; at most 2000 buckets. The answer
  carries `source: "mongodb"`.
- `GET /api/v1/collector`: the newest sample of any topic and the interval in use.
- Errors are Problem Details (`400` for a query that does not fit, `503` when MongoDB is down); OpenAPI at
  `/swagger-ui.html`.
- Catapulsar calls this API from `OverviewService` at the editable Catametricas URL (`CATAMETRICAS_URL` by
  default) and answers `503` for the overviews when Catametricas is down.
- **Forced collection (2026-11-04, orch.md decisions 50–53):** `POST /api/v1/collect?tenant&namespace` collects a scope
  now through the same claim and idempotent write as the scheduled tasks, at most once per namespace every 5 s.
  Catapulsar's overviews use it for **Collect now** and **Live** (namespace only, every 5 s, while the tab is
  visible, off after 10 minutes).
