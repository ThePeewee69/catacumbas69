# Catametricas

Catametricas collects **Pulsar topic statistics into MongoDB**, so Catapulsar can show how much traffic passed
through every topic, namespace and tenant, and how the backlog evolved — not just Pulsar's live numbers,
which reset whenever a broker restarts.

Engineering rules: [`../orch.md`](../orch.md). Design: [ADR 0007](../docs/adr/0007-catametricas-statistics.md).

## How it works

- Every **60 s** by default (15–600 s, editable in Catapulsar → ⚙️ Settings) Catametricas lists every tenant,
  namespace and topic and reads each topic's statistics, with at most 8 calls in flight. System topics
  (`__…`) are skipped.
- Pulsar's counters (messages and bytes in/out) are cumulative and **reset when a topic is reloaded**.
  Catametricas stores the last counter values per topic and adds only the difference since the previous
  collection; a counter that went down is treated as a reset. The very first collection only sets the
  starting point.
- Results are written with `$inc` bulk upserts:

| Collection | Content | Kept |
|---|---|---|
| `contario_topic_snapshots` | Latest state per topic: backlog, rates, storage, subscriptions, producers, consumers, raw counters, `active` | Forever (`active=false` once the topic is deleted) |
| `contario_topic_minutes` | Messages/bytes in and out, backlog, per topic and minute | 30 days (TTL index) |
| `contario_topic_hours` | The same per topic and hour | Forever |
| `contario_topic_totals` | All-time messages/bytes in and out per topic | Forever |
| `contario_status` | Last collection: time, duration, topics, failures, interval | — |
| `contario_locks` | Collector lease | — |

- **Several replicas are safe**: a lease in `contario_locks` lets only one instance collect; another takes
  over when the lease expires (three intervals) or immediately when the holder shuts down.
- Catapulsar reads these collections directly to draw the cluster, tenant and namespace overviews.
- **Events** (ADR 0008, orch.md §4.4): only changes of state are published on
  `catacumbas69/catametricas/events` — `collection.failed` the first time collecting fails (MongoDB lease or
  Pulsar) and `collection.recovered` on the next success. The namespace and topic are created at start-up.

## Run locally (IntelliJ on necromancer)

| Variable | Value |
|---|---|
| `PULSAR_SERVICE_URL` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` (events) |
| `PULSAR_LISTENER_NAME` | `external` |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |
| `CATAMETRICAS_COLLECT_INTERVAL_SECONDS` | *(optional)* default interval, `60` |

Run `CatametricasApplication` (port **8083**). Settings live in `application.properties` under `catametricas.*`.

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
| `/actuator/health/liveness`, `/actuator/health/readiness` | probes |
| `/actuator/health` | `collector` component: leader, last collection, topics, failures, last error |
| `/actuator/prometheus` | `catametricas_collect_duration`, `catametricas_collect_failures_total`, `catametricas_topics` |

Container image: `Dockerfile` (multi-stage, layered jar, non-root, port 8083), built from `apps/` (see above).

## Limits

- Traffic between the last collection and a counter reset (broker restart, bundle unload) is lost — at
  most one interval.
- If Catametricas is stopped for a while, the traffic of that gap is added to the first minute after it
  starts again.
- Changing `catametricas.collect.minute-retention` after the TTL index exists needs the index
  `minutes_ttl` to be dropped first.
