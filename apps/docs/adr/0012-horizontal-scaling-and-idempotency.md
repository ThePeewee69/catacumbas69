# 12. Every service scales horizontally; all writes are idempotent

Date: 2026-10-31 · Status: Accepted (every service; Cataponte in phase 5, 2026-11-01) · Supersedes: ADR 0002 and
the collector lease of ADR 0007

## Context

Cataponte runs one replica (ADR 0002) and Catametricas runs active/standby behind a lease (ADR 0007).
We want any number of instances of every service, all doing work, with retries and duplicates harmless.

## Decision

**Catapulsar and Cataudit** are stateless apart from MongoDB: any number of replicas behind the Service.
Cataudit consumes the audit topic with a `Shared` subscription; inserts are keyed by event id.

**Cataponte**: every replica runs every enabled bridge; **Pulsar distributes the messages** through the
bridge's subscription type, so no lock or leader election is needed:

| Subscription | With N replicas | Bridge mode |
|---|---|---|
| `Failover` | one replica receives, the others take over if it dies | ordered |
| `Key_Shared` | messages spread by key; order kept per key | ordered per key |
| `Shared` | messages spread round-robin | unordered |
| `Exclusive` | only one replica can subscribe — **not allowed any more** | — |

Traffic counters stay correct because each replica adds (`$inc`) only what it relayed itself.

**Catametricas** shares the collection work through Pulsar instead of a lease:

1. Every instance runs a light scheduler that, every interval, publishes one **task per namespace** to
   `catacumbas69/catametricas/collect-tasks` with key and id `<tenant>/<namespace>@<bucket start>`.
   N instances publish N copies of each task; that is expected.
2. All instances consume the topic with a `Shared` subscription, so tasks are spread over them.
3. Before working, an instance **claims** the task with an insert into `catametricas_task_runs`
   (`_id` = task id, TTL a few hours). A duplicate key means another instance already did it: ack and skip.
4. Counter deltas are applied with a **conditional update** of the topic snapshot
   (`findAndModify` where `sampleTime < new sampleTime`); only the caller that moved the snapshot forward
   adds the delta to the minute/hour/total documents. A replayed or late sample changes nothing.

**General idempotency rules** for every service:

- Document ids are deterministic (event id, bucket key, task id), never random for data that may be retried.
- Writes are upserts or conditional updates; `$inc` only after a successful guard that cannot succeed twice.
- Consumers acknowledge after the write; redelivery is harmless by the rules above.
- REST `PUT`/`DELETE` and enable/disable stay idempotent (RES-10); registrations at start-up (audit bridges)
  are upserts by name.

## Consequences

- Throughput grows with replicas; losing a replica only moves its work to the others (Pulsar redelivers its
  unacknowledged messages).
- With `Failover`, extra Cataponte replicas are standbys for ordered bridges; spreading load needs
  `Shared`/`Key_Shared`.
- The scheduler's duplicate tasks cost a few messages per namespace and interval; the claim keeps the work
  single.
- `catametricas_task_runs` and the conditional snapshot update add one round-trip per task and per topic.

## Implementation for Catametricas (phase 4, 2026-11-01)

- Every instance publishes, just after the start of each interval (aligned to the epoch), one `CollectTask`
  per namespace to `catacumbas69/catametricas/collect-tasks`, key and id `<tenant>/<namespace>@<interval start>`.
- Every instance consumes the topic with the `Shared` subscription `catametricas` and a receiver queue of 1, so
  tasks spread over the instances.
- The claim is an insert into `catametricas_task_runs` (`_id` = task id, TTL 6 h); the run's result is added to
  the same document. A duplicate key means another instance has the task: acknowledge and skip.
- Each topic's snapshot is replaced with a conditional `findAndReplace` (`_id` = topic and
  `collectedAt < sample time`, upsert) that returns the replaced snapshot; the delta is computed from that
  snapshot and added only by this caller. A duplicate key on the upsert means a newer snapshot exists:
  nothing is counted.
- Every task is run, however late. A failed task releases its claim and is negatively acknowledged; Pulsar
  delivers it again after 10 s, doubling up to 5 min. A namespace that no longer exists marks its topics as
  gone.
- The lease and its collection are removed.

## Implementation for Cataponte (phase 5, 2026-11-01)

- Every replica runs every enabled bridge. The API refuses `Exclusive` (`400`).
- On a `Failover` subscription each worker follows the broker's consumer events (`becameActive` /
  `becameInactive`), so it knows whether it receives or stands by.
- Each replica writes its workers' status to `cataponte_worker_status` every 5 s (`_id` = `<bridge>@<instance>`,
  TTL 5 min) and removes its reports on shutdown. `GET /api/v1/bridges/status` merges this replica's live status
  with the other replicas' reports from the last 30 s: the most advanced state, summed counters, `replicas` and
  `activeReplicas`. Without MongoDB a replica answers for itself.
- The loop check on writes stays in-process; a loop created by two simultaneous writes on different replicas is
  blocked by the reconciler (ADR 0005).
