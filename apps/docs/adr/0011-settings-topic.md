# 11. Shared settings live in Catapulsar and are published on a compacted topic

Date: 2026-10-31 · Status: Accepted, publishing implemented in phase 1 (readers: phase 3)

## Context

Settings are edited in Catapulsar's UI (refresh interval, Cataponte URL, collection interval…). Other
services need some of them, and every replica of every service must see a change, also replicas that start
after it. Today Catametricas reads Catapulsar's collection, and Catapulsar keeps the Cataponte URL in each
replica's memory, so other replicas miss changes.

## Decision

- **Catapulsar owns all shared settings** in its collection and remains the only place to edit them (REST,
  audited through its events).
- On every change, and once at start-up for every setting, Catapulsar publishes the value to
  `catacumbas69/catapulsar/settings` with **message key = setting id** (e.g. `catametricas.collect-interval-seconds`).
  The topic has **compaction** enabled, so it always holds the latest value per key.
- Every service (and every Catapulsar replica) opens a Pulsar **`TableView`** on the topic at start-up. A
  TableView is a reader without a subscription: it reads the topic from the beginning (compacted), builds a
  map *setting id → latest value*, and keeps updating it as new values arrive. Nothing is "consumed": no
  acknowledgement, no cursor, so every start reads the full current state again. A missing key means "use
  the configured default".
- The settings namespace has **infinite retention** (`retention -1/-1`) so values are never deleted by
  age, and compaction keeps the topic small (one message per key). Deleting a setting is publishing an
  empty value (tombstone) for its key.
- A `SettingChanged` event is also published on `catacumbas69/catapulsar/events` for the audit trail.

## Consequences

- Services start with the right values even when Catapulsar is down, and all replicas converge within
  seconds of a change.
- The settings topic is a public contract: keys and value formats are documented in `orch.md`; values are
  JSON (`{"value": 60}`), so new fields can be added.
- A value written directly to MongoDB is not seen by other services until Catapulsar republishes it (at its
  next start-up); settings must be changed through the API.

## Implementation (phase 1, 2026-10-31)

- Catapulsar declares the topic as a compacted `TopicSpec`. It is created at start-up with topic policies:
  retention `-1/-1` and a compaction threshold of 1 MB. The namespace keeps the 7-day retention of the events.
- Values use the `SettingValue` schema `{"value": "<text>"}` (text, so one schema fits every setting) instead
  of a typed `{"value": 60}`.
- Keys already use the owner's prefix: `catapulsar.cataponte-url`, `catapulsar.refresh-interval-seconds` and
  `catametricas.collect-interval-seconds`. The MongoDB document ids keep their old names until the phase 3
  migration.
- Catapulsar publishes on every change and, at start-up, publishes every setting: its stored value, or a
  tombstone when the setting is at its default. Nobody reads the topic until phase 3.
