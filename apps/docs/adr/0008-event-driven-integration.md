# 8. Services integrate through REST commands and Pulsar events

Date: 2026-10-31 · Status: Proposed

## Context

Catapulsar, Cataponte and Catametricas share MongoDB collections (ADR 0006, ADR 0007): Catapulsar reads
Catametricas' statistics, Catametricas reads Catapulsar's settings and every app writes `audit_logs`.
A change of one document shape breaks other services, and services cannot be deployed, scaled or
replaced on their own. We already run Pulsar, so it can carry events between our own services too.

## Decision

- **Hybrid integration.** Commands and queries use REST (browser → Catapulsar → Cataponte /
  Catametricas). Facts ("something happened") are published as **events** on Pulsar. No service reads or
  writes another service's collections.
- **One namespace per app** in a dedicated tenant `catacumbas69`:

  | Topic | Producer | Purpose |
  |---|---|---|
  | `persistent://catacumbas69/catapulsar/events` | Catapulsar | topic, subscription, backlog, produce, peek, settings actions |
  | `persistent://catacumbas69/catapulsar/settings` | Catapulsar | current value of every shared setting (compacted, ADR 0011) |
  | `persistent://catacumbas69/cataponte/events` | Cataponte | bridge created/updated/deleted/enabled/disabled/blocked/migrated |
  | `persistent://catacumbas69/catametricas/events` | Catametricas | collection completed/failed |
  | `persistent://catacumbas69/catametricas/collect-tasks` | Catametricas | work items shared by all instances (ADR 0012) |
  | `persistent://catacumbas69/cataudit/audit` | Cataponte bridges | everything to be audited (ADR 0009) |

- **Envelope: CloudEvents 1.0 in JSON**, with a JSON schema registered in Pulsar for each topic:
  `id` (UUID), `source` (`/catacumbas69/<app>`), `type` (`com.catacumbas69.<app>.<entity>.<action>.v1`),
  `subject` (bridge id, topic, setting id…), `time`, `datacontenttype` and `data` (`actor`, `before`,
  `after`, details). The message key is the `subject`, so events about the same thing stay in order.
- **Publish after commit, with retries.** A service first stores its change, then publishes the event with
  bounded retries and backoff; a failure is logged with the full event at `ERROR`. Accepted risk: an event
  is lost if the process dies between the commit and a successful publish.
- **Consumers are idempotent**: they use the event `id` (or a deterministic key) so a redelivered or
  duplicated event has no extra effect (ADR 0012).
- Event `type`s are versioned (`.v1`); a breaking change creates `.v2`, published alongside `.v1` until
  every consumer has moved.

## Consequences

- Services can be deployed, scaled and restarted independently; a consumer that is down catches up from
  its subscription backlog.
- Each topic needs retention and backlog quotas sized for the longest outage we accept (default: 7 days).
- One more moving part per app (a Pulsar producer); events are asynchronous, so a UI that needs the result
  at once must still call the owner's REST API.
- **Each app creates what it needs at start-up** through the Pulsar admin API: the tenant (if missing), its
  namespace and topics, and their policies (retention, backlog quota, compaction, schema). Creation is
  idempotent ("already exists" is success), so N replicas starting together are safe. Every app therefore
  needs admin access to Pulsar (`PULSAR_SERVICE_URL`).
- The CloudEvents envelope, the publisher with retries, the topic names and the settings reader live in a
  small shared library, **`catacommons`**, used by every app.
- Until an authentication proxy exists (SEC-2), `data.actor` is the client IP address.
