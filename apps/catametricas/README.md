# Catametricas

Catametricas collects **Pulsar topic statistics into MongoDB** and serves them: how much traffic passed through
every topic, namespace and tenant, and how the backlog evolved. Pulsar's own numbers reset whenever a broker
restarts; these do not. It is the only metrics API of the apps (ADR 0013): Catapulsar's overviews call it.

Engineering rules: [`../orch.md`](../orch.md). Design: [ADR 0007](../docs/adr/0007-catametricas-statistics.md),
[ADR 0012](../docs/adr/0012-horizontal-scaling-and-idempotency.md), [ADR 0013](../docs/adr/0013-metrics-sources.md).

## How it works

```
every instance, each interval ──▶ catametricas/collect-tasks ──▶ every instance (Shared subscription)
  one task per namespace            key = <tenant>/<ns>@<start>      claim → read namespace → write MongoDB
```

- **Interval**: every **60 s** by default (15–600 s), editable in Catapulsar → ⚙️ Settings and read from the
  settings topic (ADR 0011). Intervals are aligned to the epoch, so every instance computes the same task ids.
- **Tasks**: just after the start of each interval, every instance lists the namespaces and publishes one task
  per namespace to `catacumbas69/catametricas/collect-tasks`. N instances publish N copies; that is expected.
- **Claims**: all instances consume the topic with the `Shared` subscription `catametricas` (receiver queue 1,
  so tasks spread). Before working, an instance inserts the task id into `catametricas_task_runs`; a duplicate
  key means another instance has it, so the task is skipped.
- **Reading**: the task's namespace is listed and each topic's statistics are read, with at most 8 calls in
  flight. System topics (`__…`) are skipped. A namespace that no longer exists marks its topics as gone.
- **Counting**: Pulsar's counters (messages and bytes in/out) are cumulative and **reset when a topic is
  reloaded**. Each topic's snapshot keeps the last counters and is replaced only by a newer sample (a
  conditional `findAndReplace` that returns the snapshot it replaced). Only the instance that replaced it adds
  the difference, so a repeated or late task never counts twice. A counter that went down is a reset. The first
  collection of a namespace only sets the starting point.
- **Failures**: if Pulsar or MongoDB fails, the claim is released and the task is delivered again after 10 s,
  doubling up to 5 min. Every task is run, however late.
- **Several replicas are safe and all collect**; there is no leader.

| Collection | Content | Kept |
|---|---|---|
| `catametricas_topic_snapshots` | Latest state per topic: backlog, rates, storage, subscriptions, producers, consumers, raw counters, `active` | Forever (`active=false` once the topic is deleted) |
| `catametricas_topic_minutes` | Messages/bytes in and out, backlog, per topic and minute | 30 days (TTL index) |
| `catametricas_topic_hours` | The same per topic and hour | Forever |
| `catametricas_topic_totals` | All-time messages/bytes in and out per topic | Forever |
| `catametricas_task_runs` | Task claims: who, when, and the result | 6 h (TTL index) |

**Events** (ADR 0008, orch.md §4.4): only changes of state of each instance are published on
`catacumbas69/catametricas/events`: `collection.failed` the first time scheduling, claiming or collecting fails,
and `collection.recovered` on the next successful task. The namespace and the topics are created at start-up,
and the `audit-catametricas` system bridge is registered in Cataponte (`CATAPONTE_URL`) so they reach Cataudit
(ADR 0009).

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/topics?tenant=&namespace=` | Every topic or partition of the scope: `current` state (absent once the topic is gone) and all-time `total` traffic |
| `GET` | `/api/v1/series?tenant=&namespace=&groupBy=&from=&to=&step=` | Messages and bytes in and out, and backlog, per bucket, summed per `groupBy` = `tenant`, `namespace` (needs `tenant`) or `topic` (needs `namespace`; partitions summed). `step` `1m`–`59m` reads minutes, whole hours (`1h`, `6h`) read hours; `to` defaults to now; at most 2000 buckets |
| `GET` | `/api/v1/collector` | Newest sample of any topic (`lastCollectionAt`) and the interval in use |

Errors are RFC 9457 Problem Details (`400` for a query that does not fit, `503` when MongoDB is down). Full
contract: `http://localhost:8083/swagger-ui.html`. The browser reaches the statistics only through Catapulsar's
overviews.

## Run locally (IntelliJ on necromancer)

| Variable | Value |
|---|---|
| `PULSAR_SERVICE_URL` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` (tasks, events, settings) |
| `PULSAR_LISTENER_NAME` | `external` |
| `CATAPONTE_URL` | `http://localhost:8082` (audit bridge registration) |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |
| `CATAMETRICAS_COLLECT_INTERVAL_SECONDS` | *(optional)* default interval, `60` |

IntelliJ *Environment variables* (replace `<user>` and `<password>`; `.idea/` stays out of Git, CFG-2):

```
PULSAR_SERVICE_URL=http://192.168.86.35:31080;PULSAR_BROKER_URL=pulsar://192.168.86.35:31650;PULSAR_LISTENER_NAME=external;SPRING_DATA_MONGODB_URI=mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true;CATAPONTE_URL=http://localhost:8082
```

Start it **third**: Cataponte → Cataudit → Catametricas → Catapulsar. It registers its audit bridge in
Cataponte, and starting it before Catapulsar gives the overviews data sooner (reasons in
[orch.md §2.1](../orch.md#21-configuration-contract)).

**Second instance**: copy the run configuration, add `;SERVER_PORT=8093` and allow multiple instances (*Modify
options*). Both collect; each task is claimed by one of them (`catametricas_task_runs`), so nothing is counted
twice.

Run `CatametricasApplication` (port **8083**). In Catapulsar set `CATAMETRICAS_URL=http://localhost:8083`, or
change the Catametricas URL in ⚙️ Connection Settings. Settings live in `application.properties` under
`catametricas.*` (`catametricas.tasks.*` for the task topic).

## Build and test

The app is a module of the Maven reactor in `apps/` (with the shared library `catacommons`). From `apps/`:

```bash
mvn clean install                       # everything: catacommons + every app, tests + Checkstyle
mvn -pl catametricas -am verify                # this app and catacommons only
docker build -f catametricas/Dockerfile -t catametricas .   # image (BuildKit), context = apps/
```

Unit tests need no Docker, MongoDB or Pulsar.

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | probes; readiness does not depend on Pulsar, so the API stays up |
| `/actuator/health` | `collector` component: whether this instance consumes tasks, its last task, last error |
| `/actuator/prometheus` | `catametricas_tasks_total{outcome=done\|duplicate\|invalid\|retry}`, `catametricas_collect_duration` (one namespace), `catacommons_messages_total` |

Container image: `Dockerfile` (multi-stage, layered jar, non-root, port 8083), built from `apps/` (see above).

## Limits

- Traffic between the last collection and a counter reset (broker restart, bundle unload) is lost — at
  most one interval. The same holds for a topic whose write failed halfway through a task.
- If every instance is stopped for a while, the traffic of that gap is added to the minute of the first task
  after they start again.
- The first interval of a new namespace only sets the starting point, so its traffic is not counted.
- Changing `catametricas.collect.minute-retention` or `catametricas.tasks.run-retention` after the TTL index
  exists needs the index (`minutes_ttl`, `task_runs_ttl`) to be dropped first.
