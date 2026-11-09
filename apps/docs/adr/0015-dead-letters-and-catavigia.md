# 15. Dead letters for poison messages, and Catavigia: alerts with history

Date: 2026-11-06 · Status: Accepted (2026-11-06); phase 7a implemented (2026-11-06), 7b implemented (2026-11-08, steps 1–3) · Decisions: orch.md 42–49, 66–80

## Context

Our `Shared` consumers negatively acknowledge a message they cannot handle, and Pulsar delivers it again,
without limit (RES-15). A message that can never be handled (a *poison* message) is retried for ever, and
nobody notices. More generally, nothing watches the platform: a bridge stuck in `FAILED` or `BLOCKED`, collection
that stopped, a backlog that keeps growing, an app, a broker, a bookie or MongoDB that is down. They show up only
when someone opens the right screen.

## Decision

### 1. Dead-letter topics (orch.md 42, 43)

- After **N deliveries** (shared setting `dlq.max-deliveries`, default **10**), Pulsar moves the message to a
  **dead-letter topic** (DLQ) and the consumer carries on. This uses Pulsar's own `DeadLetterPolicy`, which works only
  for `Shared` and `Key_Shared`.
- **Who gets one:**
  - Cataudit: `catacumbas69/cataudit/audit`, subscription `cataudit`;
  - Catametricas: `catacumbas69/catametricas/collect-tasks`, subscription `catametricas`. This replaces decision 33:
    a task that fails N times goes to the DLQ instead of being run however late;
  - Cataponte: every **unordered** bridge with a `Shared` or `Key_Shared` subscription (an ordered `Key_Shared` bridge
    has none: a DLQ would break the order, and its replays would count as deliveries). `Failover` bridges (ordered) keep today's
    replay; Pulsar has no DLQ for them.
- **Name and place:** `<topic>-<subscription>-DLQ`, in the topic's own namespace (for a bridge, the user's
  namespace). It is created with the subscription **`dlq`** (`initialSubscriptionName`), so its backlog is the number
  of poison messages still to be looked at, and they are kept until someone acts.
- **N is shared**: changing it in Catapulsar's Settings reaches every replica through the settings topic (ADR 0011).
  The policy is set when a consumer subscribes, so each app re-subscribes its consumers when N changes.
- **Only a message can be poison, not an outage.** If every failure counted, MongoDB being down for two minutes
  would send every audit event to the DLQ, and a destination being down would empty a bridge into it. So:
  - Cataudit and Catametricas **pause the consumer** (`consumer.pause()`) while MongoDB or Pulsar's admin API is
    unavailable, and **resume by themselves** when it answers again. Messages wait in the backlog and their delivery
    count does not grow. Only a failure of *that* message counts (it cannot be read, it breaks a rule, an unexpected
    exception).
  - A Cataponte bridge whose destination producer fails for every message (destination down, unreachable) goes to
    `FAILED` and stops consuming, as an ordered bridge already does. Only a message the destination refuses on its
    own (too large, schema not compatible) is negatively acknowledged and can reach the DLQ.

### 1b. Consumer control (review point 1)

Each consumer of ours (Cataudit `audit`, Catametricas `collect-tasks`) can also be controlled by hand, **per
replica** or for all of them:

- **Pause** and **Resume**. A pause made by hand is never resumed automatically; only *Resume* ends it.
- **Check connections**: the replica pings its MongoDB and calls Pulsar's admin API now, and reports each result
  (up or down, time taken, error, when).
- **Status**: every replica publishes its consumers' state every 10 s and on every change on the compacted topic
  `catacumbas69/<app>/consumers` (key = instance): `RUNNING`, `PAUSED_AUTO` (and why) or `PAUSED_MANUAL` (and
  who), since when, and the last connection check. A tombstone is written on shutdown; an entry not updated for
  30 s is shown as gone. This goes through Pulsar rather than MongoDB because the usual reason to pause is MongoDB
  being down.
- **Commands**: Catapulsar publishes `pause`, `resume` or `check` on `catacumbas69/<app>/consumer-commands`
  (target = one instance or all; actor = browser address). Every replica reads it from the latest message (a
  Reader, nothing to acknowledge) and acts on the commands meant for it.
- **Audit**: each app publishes `consumer.paused`, `consumer.resumed` (by hand or automatically) and
  `consumer.checked` on its events topic, so Cataudit has them.
- **Catapulsar's Consumers panel** lists every consumer of every app per replica, with its state, since when, why,
  the last check, and the buttons. The `consumer.paused` alert (below) links to it.

Bridges already have this control: *Disable* and *Enable* in the Bridges panel, and `FAILED` retries by itself.
A `Failover` bridge has no DLQ, so a poison message keeps it `FAILED`; its card then offers **Skip this message**:
Cataponte acknowledges the message the bridge is stuck on (after showing it and asking for confirmation), publishes
`bridge.message-skipped` with the message id, and the bridge carries on.

### 2. Catavigia (orch.md 44, 45, 48, 49)

A new service, **Catavigia** (port **8085**), watches and **keeps the history of every alert**: opened, last seen,
closed. It only watches; it never changes anything.

**Checks**, every `alerts.check-interval` (30 s), run as **tasks on Pulsar** (review point 8):

| Alert | Severity | Source | Opens when |
|---|---|---|---|
| `poison` | warning | Catametricas (topics ending in `-DLQ`) | A DLQ has backlog on `dlq` |
| `bridge.failed`, `bridge.blocked` | warning | Cataponte `/api/v1/bridges/status` | A bridge is `FAILED` or `BLOCKED` |
| `collection.stale` | warning | Catametricas `/api/v1/collector` | No sample for 3 collection intervals |
| `backlog` | warning | Catametricas (per subscription) | A subscription's backlog, partitions summed, is above its limit: its own, or the global default (**10 000** messages). The DLQs' `dlq` and Pulsar's `__*` are left out |
| `consumer.paused` | warning | `catacumbas69/<app>/consumers` | A consumer of ours is paused (automatically or by hand) on a replica that still reports; one alert per consumer, naming the paused replicas |
| `service.down` | critical | Each app's `/actuator/health/readiness` | Catapulsar, Cataponte, Catametricas or Cataudit is not ready or does not answer; the components of its full health that are not `UP` go in the details |
| `pulsar.broker.down` | critical | Pulsar admin API | Fewer active brokers than `CATAVIGIA_EXPECTED_BROKERS` (default 1) |
| `pulsar.bookie.down` | critical | Pulsar admin API | Brokers answer but the broker health check (write and read a message) fails; the admin API lists bookies that are down too, so they are not counted |
| `mongodb.down` | critical | Catavigia's own MongoDB connection | MongoDB does not answer a ping |

- **Tasks** (like Catametricas' collection, ADR 0012): at the start of every round (aligned to the epoch) every
  replica publishes one task **per check** (`poison`, `bridges`, `collection`, `backlog`, `consumers`, `services`,
  `pulsar`) to `catacumbas69/catavigia/check-tasks`, key `<check>@<round start>`. Every replica consumes it with
  the `Shared` subscription `catavigia` (consumer `catavigia@<instance>`), so the checks spread over the replicas;
  a task is claimed by inserting its id into `catavigia_task_runs` (TTL 6 h), and a duplicate means another replica
  has it. The debounce counts live in MongoDB, `catavigia_observations` (`<type>|<subject>`: rounds seen in a row,
  rounds clear in a row, last round), since consecutive rounds may run on different replicas.
- The `check-tasks` consumer works like those of phase 7a: it **pauses** while MongoDB or Pulsar's admin API is
  down and resumes by itself, it can be paused, resumed and checked from the **Consumers** panel (status and
  commands on `catacumbas69/catavigia/consumers` and `consumer-commands`), and a task that keeps failing goes to
  `check-tasks-catavigia-DLQ`.
- **MongoDB down** (review point 9): `mongodb.down` is not a task. Every replica pings its MongoDB every round and
  keeps the alert **in memory** while it fails: it is returned by the API, counted in the badge and the metric, and
  published as `alert.opened` (subject = the replica). The other checks stop meanwhile (their consumer is paused).
  When MongoDB answers again the replica writes it straight to the archive, with its real opening time and
  duration, and publishes `alert.closed`.
- **Catametricas, per subscription** (review points 10–12): the topic snapshot gains the list of its subscriptions
  (name, type, backlog, unacked, consumers, rate out, and the delivered counters), and the topic's minute and hour
  documents gain a map `subs` with one entry per subscription: backlog (last and maximum of the period), messages
  and bytes delivered (counter differences, as for topics) and unacked (last). The key is the subscription name
  with `%`, `.` and `$` escaped (`%25`, `%2E`, `%24`); each entry also holds the real name. Same retention as the
  topic's (30 days of minutes, hours for ever). New API: `GET /api/v1/subscriptions?tenant=&namespace=&topic=`
  (current, with backlog) and `GET /api/v1/subscriptions/series?topic=&subscription=&period=`.
- **Defaults in Settings:** every threshold has a global default editable in Catapulsar's Settings → Alerts:
  backlog limit, how many intervals make collection stale, check interval, debounce, and N deliveries.
- **Limit per subscription:** a subscription can have its own backlog limit, set on its row in the topic view
  (Subscription Details: "limit: global (10 000) ✎"). Catavigia stores it in `catavigia_backlog_limits`
  (`PUT`/`DELETE /api/v1/limits/backlog?topic=&subscription=`) and publishes `limit.changed`; removing it goes back
  to the global default.
- **Debounce:** an alert opens after **2** checks in a row that see the problem, and closes after **2** that do not,
  so a single slow answer does not flap.
- **Seen:** an open alert can be marked **seen** (`POST /api/v1/alerts/{id}/seen`; who and when are kept and
  published as `alert.seen`). It stays open but no longer counts in the badge. If it closes and the problem comes
  back, a new alert opens, not seen.
- **Several replicas are safe** (ADR 0012): each check runs once per round (the claim), and an open alert has a
  deterministic key `<type>|<subject>` with a unique index over open alerts only. Only the replica whose write
  succeeded publishes the event.
- **Storage:** `catavigia_alerts` holds the **open** alerts (type, subject, severity, details, openedAt,
  lastCheckedAt, seenBy/seenAt). When an alert closes it **moves to the archive**, `catavigia_alerts_archive`
  (the same fields plus closedAt and how long it lasted), kept **for ever**, like the audit.
- **Events:** `alert.opened`, `alert.seen`, `alert.closed`, `limit.changed` and setting changes on
  `catacumbas69/catavigia/events`. The
  `audit-catavigia` bridge is registered at start-up like every app's, so Cataudit stores them.
- **Metrics:** `catavigia_alerts_open{type,severity}` on `/actuator/prometheus`, for Grafana once Prometheus runs.
  External notifications (Telegram, e-mail) come later.
- **API:** `GET /api/v1/alerts` (open), `GET /api/v1/alerts/archive?type=&subject=&from=&to=&page=&size=`,
  `GET /api/v1/alerts/{id}`, `GET /api/v1/alerts/summary` (open and not seen, by severity),
  `POST /api/v1/alerts/{id}/seen`, and the limits above. Catapulsar forwards `/api/v1/alerts/**`, as it does for the
  audit.
- **Settings** (settings topic, editable in Catapulsar): `dlq.max-deliveries`, `alerts.backlog-limit`,
  `alerts.collection-stale-intervals`, `alerts.check-interval`, `alerts.debounce`. The app URLs come from the same keys Catapulsar already uses for its
  connections; Catapulsar's own URL from `CATAPULSAR_URL` (review point 13).
- **Start order:** Cataponte → Cataudit → Catametricas → **Catavigia** → Catapulsar.

### 3. Actions are taken by the owner (orch.md 46)

Catavigia links each alert to the place where it is fixed, and the owner of the resource does it, with its own
audit (ADR 0010):

| Alert | Action | Where |
|---|---|---|
| `poison` | Peek the DLQ, **send back** a message to the original topic, **discard** it (or all) | Catapulsar (new endpoints, audited) |
| `bridge.*` | Open the bridge: disable, edit, enable | Cataponte, through Catapulsar's Bridges panel |
| `backlog` | Open the topic's subscription: peek, clear backlog | Catapulsar topic view |
| `consumer.paused` | Resume, check connections | Catapulsar Consumers panel (commands to the app) |
| `service.down`, `mongodb.down`, `pulsar.*` | Information, and a link to Settings → Connections | — |

### 4. Catapulsar (orch.md 47)

- **Settings → Connections** gains Catavigia's URL (`catavigia.url`, default `CATAVIGIA_URL`), with its check
  button, like the other apps (review point 13). Catapulsar forwards `/api/v1/alerts/**` and `/api/v1/limits/**`.
- **History of a subscription** (review point 12): expanding a subscription's row in *Subscription Details* shows a
  chart of its backlog and messages delivered over 1 h / 24 h / 7 d / 30 d, from Catametricas. This is an explicit
  exception to OBS-10 for history only; the row's current numbers still come from Pulsar.
- The Consumers panel also lists Catavigia's `check-tasks` consumer.
- An **Alerts** button in the top bar, with the number of open alerts coloured by the worst severity.
- The **Alerts panel** has two tabs: *Open* (with *Mark as seen* and an action button per alert) and *Archived*
  (filters: type, subject, period).
- The **DLQ view** of a poison alert lists the messages with their original topic, properties and delivery count,
  and offers *Send back*, *Discard* and *Discard all*, each confirmed first.
- **Settings → Alerts** tab (7b): backlog limit, collection stale intervals, check interval, debounce, and the N
  deliveries that had their own Dead letters tab in 7a (review point 24).
- **Implemented in 7a:** the DLQ view is the *Dead letters* tab of the Consumers panel; it lists the DLQs of the
  apps' consumers and of the bridges, peeks up to 50 messages, and sends back or discards **any** of them, not only
  the first. Catapulsar does it with a short-lived consumer on the DLQ's `dlq` subscription that receives until it
  finds the message id (up to 10 000 messages), then acknowledges it after the resend (sent back with its key and
  event time, without the DLQ properties). *Discard all* clears the `dlq` backlog; *Send all back* keeps the messages
  without an original topic. Endpoints: `GET /api/v1/consumers`, `POST /api/v1/consumers/commands`,
  `POST /api/v1/dead-letters/{send-back,discard,send-back-all,discard-all}`, `/api/v1/settings/dead-letters`.

## Consequences

- Poison messages stop being retried for ever, and are kept where someone can look at them and decide.
- An outage no longer turns into a pile of false poison messages, at the price of a pause in the consumer: the
  backlog grows during the outage, which the `backlog` alert shows.
- A new service to build, deploy and keep running; if Catavigia is down there are no alerts. Catapulsar shows that
  Catavigia is unreachable.
- `Failover` bridges still have no DLQ. A poison message there keeps the bridge `FAILED`, which raises
  `bridge.failed`.

## Phasing

- **7a:** dead-letter topics in the three apps; pausing on outages, with automatic resume; consumer control
  (status and commands topics, Consumers panel, Check connections); Catapulsar's DLQ view and its actions; N in
  Settings.
- **7b:** Catavigia (checks, open alerts and archive, seen, limits per subscription), Catametricas' backlog per
  subscription, the Alerts panel and Settings → Alerts. In steps: (1) Catametricas per subscription and its API;
  (2) Catavigia; (3) Catapulsar (Catavigia URL, Alerts panel, Settings → Alerts, limit and chart on the
  subscription row, Catavigia in the Consumers panel).

## Review (2026-11-06)

1. Pause on outages: yes, resuming automatically, with control by hand (pause, resume) and a manual check of the
   MongoDB and Pulsar connections (section 1b).
2. Backlog alert per subscription, with a global default in Settings; every threshold has a global default.
3. An open alert can be marked as seen.
4. Closed alerts move to an archive, kept for ever.
5. Phased in 7a and 7b.

6. Consumer status and commands go **through Pulsar topics**, so they work while MongoDB is down.
7. `Failover` bridges get **Skip this message** (section 1b).

### Review of the 7b design (2026-11-06)

8. Checks run as **tasks on Pulsar**, one per check and round, claimed in MongoDB; the debounce counts are kept in
   MongoDB; the `check-tasks` consumer has the 7a control (Consumers panel, pause on outages, DLQ).
9. `mongodb.down` is kept **in memory** by each replica and published as an event; the other checks stop until
   MongoDB answers; then it is archived with its real duration.
10. Catametricas keeps the **history** per subscription, not only the current state.
11. Stored **inside the topic's documents** (map `subs` in minutes and hours, list in the snapshot): backlog (last
    and maximum), messages and bytes delivered, unacked.
12. Shown when a subscription's row is expanded in *Subscription Details* (chart, from Catametricas).
13. Catavigia's URL is a setting in Settings → Connections; Catavigia reads Catapulsar's URL from `CATAPULSAR_URL`.

### Review of the checks (2026-11-07)

14. Brokers are counted against an **expected number** (`CATAVIGIA_EXPECTED_BROKERS`, default 1).
15. Bookies are found **through the broker health check**: Pulsar's admin API lists every known bookie, running or
    not, and the Pulsar on MicroShift is standalone (embedded bookie). Brokers answering but the health check
    failing opens `pulsar.bookie.down`; bookies are not counted.
16. `consumer.paused` is **one alert per consumer** (`<app>/<consumer>`), its details naming the paused replicas.
17. `backlog` leaves out the DLQs' `dlq` subscription (that is `poison`) and Pulsar's own `__*`.
18. `service.down` reads **readiness**; the components of the full health that are not `UP` go in the details.
19. With Pulsar down no check runs (**accepted**). A check that cannot read its source is **delivered again**, like
    any failed task, and goes to the DLQ after `dlq.max-deliveries`; a task of an old round still runs but
    changes no alert, since the debounce counts only newer rounds.

### Review of Catapulsar's alerts (2026-11-08)

20. The **Alerts** button shows the number of open alerts not seen: red if one is critical, orange otherwise,
    nothing when none, grey `?` when Catavigia does not answer.
21. Both tabs are **expandable tables**, like the Audit panel, with *Mark seen* and the action on each row.
22. The action button opens the place where it is fixed **with the resource selected** (the DLQ peeked, the bridge
    shown, the topic view with the subscription's history open).
23. The badge asks Catavigia **every 30 s**, whatever the auto-refresh, while the browser tab is visible.
24. Settings → **Alerts** holds the four thresholds **and** the deliveries before the DLQ; the Dead letters tab
    goes. The API is `/api/v1/settings/alerts` and `/api/v1/settings/alerts/{name}`.
25. A subscription's row gets a **Limit** column edited in place and a **📈** button opening its chart: backlog,
    maximum shaded, delivered bars and the limit, 24 h by default (1 h, 7 d, 30 d).
26. Catavigia's URL is in Settings → Connections, with a check button; `catavigia` is a component of Catapulsar's
    health. Besides one by one, **Mark all seen**.
27. Marking as seen and changing a limit are **audited by both apps**: Catapulsar publishes `alert.seen-requested`
    and `limit.change-requested` with Catavigia's status, as for the bridges. The archive filters by type, subject
    and period.
