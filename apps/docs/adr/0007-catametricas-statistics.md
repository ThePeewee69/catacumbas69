# 7. Catametricas collects topic statistics; Catapulsar reads them from MongoDB

Date: 2026-10-31 · Status: Accepted

## Context

Catapulsar should show, per tenant and per namespace, the backlog and how many messages passed through, with
history kept in MongoDB. Pulsar only reports statistics **per topic**, and its traffic counters
(`msgInCounter`, `msgOutCounter`, `bytesInCounter`, `bytesOutCounter`) are cumulative and **reset** when a
topic is loaded again (broker restart, bundle unload). Reading them on demand cannot give history or totals.

## Decision

- A dedicated service, **Catametricas** (`:8083`), reads every topic's statistics on an interval (default 60 s,
  15–600 s) with bounded parallelism and skips system topics (`__…`).
- It stores **deltas**: the last raw counters are kept per topic in `contario_topic_snapshots`; a value
  lower than before is a reset and counts in full; the very first collection only sets the baseline. Each
  collection adds (`$inc`) to `contario_topic_minutes` (TTL 30 days), `contario_topic_hours` (forever) and
  `contario_topic_totals` (forever), and records backlog per bucket. Deleted topics keep their history
  (`active=false`).
- Data is stored **per topic**; namespaces and tenants are sums computed when read, and partitions are
  summed into their topic (`baseName`).
- **Several replicas are allowed**: a lease document in `contario_locks` (atomic upsert on `_id`) lets one
  instance collect; another takes over after three intervals or as soon as the holder shuts down.
- The interval is edited in Catapulsar (⚙️ Settings), stored in `pulsario_settings`
  (`contario.collect-interval-seconds`) and audited; Catametricas reads that document before every collection.
- Catapulsar **reads Catametricas' collections directly** (no Catametricas API) and builds the cluster, tenant and
  namespace overviews; history is summed per row and bucket by MongoDB.

These two cross-service reads are explicit exceptions to ARCH-1. The field names are a shared contract in
`catametricas/.../model/Schema.java`, mirrored by `catapulsar/.../model/CatametricasSchema.java`.

## Consequences

- Overviews are fast (a few MongoDB queries) and show data that survives broker restarts; "current" values
  are at most one interval old, and the UI shows when Catametricas last collected.
- Traffic between the last collection and a counter reset is lost (at most one interval). After a Catametricas
  outage, the traffic of the gap lands in the first minute after it restarts.
- Catapulsar and Catametricas must be changed together when the collections change shape (MONGO-2).
- Per-topic storage grows with the number of topics: about 1,440 minute documents per topic per day,
  removed after 30 days, plus 24 hour documents per topic per day kept forever.
