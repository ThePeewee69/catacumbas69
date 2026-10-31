# 13. Catametricas serves long-term history from MongoDB and, later, short-term from Prometheus

Date: 2026-10-31 · Status: Proposed

## Context

MongoDB holds per-minute history (30 days), per-hour history and all-time totals per topic. Pulsar also
exposes OpenMetrics that a Prometheus can scrape at second resolution. MicroShift has no Prometheus yet;
one is planned together with OpenMetrics collection and Loki.

## Decision

- **Catametricas is the only metrics API**. Callers never query MongoDB or Prometheus themselves.
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
