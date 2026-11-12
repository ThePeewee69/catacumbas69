# Catavigia

Catavigia **watches the catacumbas69 platform and keeps the history of every alert** (ADR 0015). Each alert is
opened, kept up to date, maybe marked as seen, and closed, and closed alerts are kept for ever. Catavigia only
watches: whoever owns a resource fixes it. Catapulsar links each alert to the right panel.

Engineering rules: [`../orch.md`](../orch.md). Decisions: [ADR 0015](../docs/adr/0015-dead-letters-and-catavigia.md)
(sections 2–4 and review points 8–19), orch.md decisions 44–49 and 70–79,
[ADR 0012](../docs/adr/0012-horizontal-scaling-and-idempotency.md).

## How it works

```
every replica, each round ──▶ catavigia/check-tasks ──▶ every replica (Shared subscription catavigia)
  one task per check          key = <check>@<round>       claim → run the check → debounce → open / close
```

- **Rounds**: every `alerts.check-interval` seconds (shared setting, default **30**, 10–600), aligned to the
  epoch, so every replica computes the same task ids. Each replica publishes one task per check at the start of
  every round; N replicas publish N copies, which is expected.
- **Claims**: a replica inserts the task id into `catavigia_task_runs` before running it. A duplicate key means
  another replica has it, so the task is skipped. Tasks spread over the replicas (receiver queue 1).
- **Debounce**: `catavigia_observations` keeps, per `<type>|<subject>`, how many rounds in a row saw the problem
  and how many did not. An alert opens after `alerts.debounce` rounds that see it (default **2**) and closes after
  as many that do not. Consecutive rounds may run on different replicas, so the counts are in MongoDB. An
  observation moves only for a round newer than the last one it counted, so a task delivered late still runs but
  changes nothing.
- **Failures** (orch.md decision 79): a check that cannot read its source (Cataponte, Catametricas, Pulsar's
  admin API), or a MongoDB failure, releases the claim. The task is then delivered again after 10 s, doubling up
  to 5 min, and after `dlq.max-deliveries` deliveries it goes to `check-tasks-catavigia-DLQ`. If MongoDB or
  Pulsar's admin API is down, the consumer **pauses** and resumes by itself. A message that is not a valid task
  goes straight to the DLQ.
- **Control**: the `check-tasks` consumer appears in Catapulsar's Consumers panel and can be paused, resumed and
  checked there (`catacumbas69/catavigia/consumers` and `consumer-commands`). Consumer names are
  `catavigia@<instance>`.
- **Pulsar down**: no task arrives, so no check runs and no alert opens until Pulsar answers again. This gap is
  accepted (decision 79), and Catapulsar already shows that Pulsar does not answer.
- **Several replicas are safe and all work.** An open alert is an insert under a unique key and a close is a
  delete, so only the replica whose write succeeded publishes the event.

## Checks

| Check | Alert | Severity | Subject | Opens when |
|---|---|---|---|---|
| `poison` | `poison` | warning | DLQ topic | A topic ending in `-DLQ` has messages on its `dlq` subscription (from Catametricas) |
| `bridges` | `bridge.failed`, `bridge.blocked` | warning | bridge name | A bridge is `FAILED` or `BLOCKED` in Cataponte's status, merged over its replicas. The details give `problem`, `lastError`, `stuckMessageId` |
| `collection` | `collection.stale` | warning | `catametricas` | No sample for `alerts.collection-stale-intervals` collection intervals (default 3) |
| `backlog` | `backlog` | warning | `<topic>\|<subscription>` | A subscription's backlog, partitions summed, is above its own limit or `alerts.backlog-limit` (default 10 000). The DLQs' `dlq` and Pulsar's `__*` subscriptions are left out |
| `consumers` | `consumer.paused` | warning | `<app>/<consumer>` | A consumer of Cataudit, Catametricas or Catavigia is paused, automatically or by hand, on a replica that still reports. The details list the paused replicas |
| `services` | `service.down` | critical | app | `/actuator/health/readiness` of Catapulsar, Cataponte, Catametricas or Cataudit is not `UP` or does not answer. The components of its full health that are not `UP` go in the details, for information |
| `pulsar` | `pulsar.broker.down` | critical | `brokers` | Fewer active brokers than `catavigia.checks.expected-brokers` (default 1) |
| `pulsar` | `pulsar.bookie.down` | critical | `healthcheck` | Brokers answer, but the broker health check (write and read a message) fails. Pulsar's admin API also lists bookies that are down, so bookies are not counted |
| — (each replica) | `mongodb.down` | critical | `catavigia@<instance>` | This replica's MongoDB does not answer a ping. See below |

**`mongodb.down`** (orch.md decision 76): no task can report it, since a claim needs MongoDB. So each replica
pings its own MongoDB every round and keeps the alert **in memory**. The alert is returned by the API (with
`local: true`), counted in the badge and the metric, and published as `alert.opened`. When MongoDB answers again
for `alerts.debounce` rounds, it is written to the archive with its real opening time, and `alert.closed` is
published. It cannot be marked as seen (`409`).

The app URLs are those set in Catapulsar's Settings → Connections (settings topic: `catapulsar.cataponte-url`,
`catapulsar.catametricas-url`, `catapulsar.cataudit-url`); until then `CATAPONTE_URL`, `CATAMETRICAS_URL`,
`CATAUDIT_URL`. Catapulsar's own URL comes from `CATAPULSAR_URL` (decision 78).

## Storage

| Collection | Content | Kept |
|---|---|---|
| `catavigia_alerts` | Open alerts: id, key `<type>\|<subject>` (unique), type, subject, severity, details, `openedAt`, `lastSeenAt`, `seenBy`/`seenAt` | While open |
| `catavigia_alerts_archive` | Closed alerts: the same, plus `closedAt` and `durationSeconds` | For ever |
| `catavigia_observations` | Debounce counts per `<type>\|<subject>`: rounds seen, rounds clear, last round, check | Removed when its alert closes |
| `catavigia_task_runs` | Task claims: who, when, findings, duration | 6 h (TTL index) |
| `catavigia_backlog_limits` | A subscription's own backlog limit, who set it and when | Until removed |

## Events

The following events are published on `catacumbas69/catavigia/events` and reach Cataudit through the
`audit-catavigia` bridge, which is registered in Cataponte at start-up:
- `alert.opened`, `alert.seen` and `alert.closed`, with subject = the alert's key;
- `limit.changed`, with the limit before and after (`global` when there is none);
- `consumer.paused|resumed|checked`.

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/alerts` | Open alerts, critical first, newest first; this replica's `mongodb.down` included (answers while MongoDB is down) |
| `GET` | `/api/v1/alerts/summary` | Open alerts, and those not seen by severity, with the `worst` one (for Catapulsar's badge); `stored: false` while MongoDB is down |
| `GET` | `/api/v1/alerts/archive?type=&subject=&from=&to=&page=0&size=50` | Closed alerts, newest closed first (`subject` = starts with; `from`/`to` on `closedAt`; at most 500 per page) |
| `GET` | `/api/v1/alerts/{id}` | One alert, open or archived (`404`) |
| `POST` | `/api/v1/alerts/{id}/seen` | Marks an open alert as seen (who = `X-Forwarded-For`), published as `alert.seen`. It stays open and leaves the badge count; `404` if not open, `409` for `mongodb.down` |
| `GET` | `/api/v1/limits/backlog` | The global default (`globalLimit`) and every subscription's own limit |
| `PUT` | `/api/v1/limits/backlog?topic=&subscription=` | Sets a subscription's own limit `{limit}` (1–10⁹); `topic` is `persistent://tenant/namespace/topic`, without partition suffix |
| `DELETE` | `/api/v1/limits/backlog?topic=&subscription=` | Back to the global default (`204`, `404` if it had none) |

Errors are RFC 9457 Problem Details: `400` for invalid input, `503` when MongoDB is down. The full contract is at
`http://localhost:8085/swagger-ui.html`. Browsers reach the API only through Catapulsar, which forwards
`/api/v1/alerts/**` and `/api/v1/limits/**` and shows the Alerts panel, the badge and the Limit column.

## Configuration

| Property / env | Default | Meaning |
|---|---|---|
| `server.port` | `8085` | |
| `PULSAR_SERVICE_URL`, `PULSAR_BROKER_URL`, `PULSAR_LISTENER_NAME` | in-cluster Pulsar | As for every app |
| `SPRING_DATA_MONGODB_URI` | in-cluster ReplicaSet | As for every app |
| `CATAPULSAR_URL` | `http://catapulsar:8081` | Catapulsar, for `service.down` |
| `CATAPONTE_URL`, `CATAMETRICAS_URL`, `CATAUDIT_URL` | `http://<app>:<port>` | Until Catapulsar's Settings has them; `CATAPONTE_URL` also registers the audit bridge |
| `CATAVIGIA_EXPECTED_BROKERS` | `1` | `pulsar.broker.down` below this |
| `catavigia.checks.http-timeout` | `5s` | Every call to an app |
| `catavigia.checks.consumer-stale-after` | `30s` | A consumer replica not reported for this long is gone |
| `catavigia.checks.initial-delay` | `15s` | Before the first round |
| `catavigia.tasks.*` | subscription `catavigia`, retries 10 s → 5 min, claims 6 h | Check tasks |

The thresholds are **shared settings**, edited in Catapulsar's Settings → Alerts and read
before every round. The keys, defaults and ranges are in catacommons `AlertSetting`:

| Key | Default | Range |
|---|---|---|
| `alerts.check-interval` | 30 s | 10–600 |
| `alerts.debounce` | 2 rounds | 1–10 |
| `alerts.backlog-limit` | 10 000 messages | 1–10⁹ |
| `alerts.collection-stale-intervals` | 3 intervals | 2–20 |

## Run locally (IntelliJ on necromancer)

Like the other apps, with the NodePort URLs and port 8085:

```
PULSAR_SERVICE_URL=http://192.168.86.35:31080
PULSAR_BROKER_URL=pulsar://192.168.86.35:31650
PULSAR_LISTENER_NAME=external
SPRING_DATA_MONGODB_URI=<NodePort 32017 URI, authSource=admin&directConnection=true>
CATAPULSAR_URL=http://localhost:8081
CATAPONTE_URL=http://localhost:8082
CATAMETRICAS_URL=http://localhost:8083
CATAUDIT_URL=http://localhost:8084
```

Keep the MongoDB URI with its password in the run configuration only (`.idea/` is git-ignored), never in the repo.

## Build and test

```
cd apps && mvn -pl catavigia -am verify       # or mvn clean install for everything
cd apps && docker build -f catavigia/Dockerfile -t catavigia .
apps/build/push.sh catavigia                    # image for MicroShift (Podman), pushed, tag in its manifest
```

Deploying to MicroShift (registry, ArgoCD, MongoDB Secret): [`microshift/apps/catacumbas69-apps/README.md`](../../microshift/apps/catacumbas69-apps/README.md).

The integration tests (`*IT`, run by failsafe in `mvn verify`) start a real Pulsar and MongoDB with
Testcontainers: `ServiceDownAlertIT` points the watched apps at a fake one (a JDK `HttpServer`) whose readiness
answers `DOWN`, waits for the `service.down` alert to open, switches it to `UP` and waits for the alert to close
and reach the archive. It sets `alerts.check-interval` to 10 s and `alerts.debounce` to 1 on the settings topic
first, so it takes about a minute. Locally they need Docker or Podman: see
[catacommons' README](../catacommons/README.md#integration-tests-catacumbascontainers).

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | Probes (application state only) |
| `/actuator/prometheus` | `catavigia_alerts_open{type,severity}`, refreshed every round |

**Start order:** Cataponte → Cataudit → Catametricas → **Catavigia** → Catapulsar.

## Limits

- With Pulsar down nothing is checked (accepted, decision 79).
- A check whose source does not answer for several rounds can send its tasks to the DLQ (decision 79). The
  source being down shows as `service.down`.
- Bookies are not counted: a bookie problem shows only when the broker health check fails.
- `mongodb.down` is per replica and in memory. A request answered by another replica does not show it.
