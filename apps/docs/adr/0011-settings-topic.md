# 11. Shared settings live in Catapulsar and are published on a compacted topic

Date: 2026-10-31 · Status: Accepted (publishing in phase 1, readers in phase 3)

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

## Implementation (phase 3, 2026-11-01)

- catacommons provides **`SharedSettings`**. At start-up it opens a `TableView<SettingValue>` on the topic,
  retried every 10 s while Pulsar is down. `get(key)` returns the current value. `listen(key, …)` gives the
  current value and every change, and a tombstone arrives as `null`, which means the default. Until the topic
  has been read, callers use their configured defaults. An app turns the reader off with
  `catacommons.settings.enabled=false`; Cataponte and Cataudit do.
- Catapulsar reads **every** setting through it: both URLs and both intervals. A change is applied at once in
  the replica that made it and reaches the other replicas through the topic. MongoDB is never read at request
  time.
- `pulsario_settings` becomes **`catapulsar_settings`**, with document id = topic key. At start-up, before
  republishing, Catapulsar copies the missing settings (a value already in the new collection wins), checks
  them and drops the old collection. The old collection is kept, with a warning, if it holds an unknown id.
- Catametricas no longer reads Catapulsar's collection; its interval comes from
  `catametricas.collect-interval-seconds` on the topic.

## No backward compatibility (2026-11-01)

The data in MongoDB was test data, so every migration was removed from the code and the old collections were
dropped by hand (orch.md decision 40). Catapulsar no longer reads `pulsario_settings` or maps its old ids;
`catapulsar_settings` is the only store.
