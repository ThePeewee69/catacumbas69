# Logs and traces on MicroShift

Loki, Tempo and Grafana Alloy on **meloes**, in the namespace `observability`, synced by ArgoCD (orch.md
decision 104). Grafana (`https://grafana.microshift.catacumbas69.com`, namespace `monitoring`) reads them through two
datasources, **Loki** and **Tempo**, next to Prometheus ([`microshift/apps/monitoring/README.md`](../monitoring/README.md)).

```
every pod's stdout ──▶ /var/log/pods ─┐
meloes' journal ──▶ /run/log/journal ─┼─▶ Alloy (DaemonSet) ──▶ Loki (30 days) ──┐
5 apps + OpenTelemetry agent ── OTLP ─┘          └────────────▶ Tempo (14 days) ─┴─▶ Grafana (Explore)
```

| | |
|---|---|
| Loki | Chart `loki` from [grafana-community/helm-charts](https://github.com/grafana-community/helm-charts) (`*`), one pod (`deploymentMode: Monolithic`), filesystem on a 20 Gi PVC (`topolvm-provisioner`), **30 days** (the compactor deletes older chunks). `https://loki.microshift.catacumbas69.com`, no authentication, home network only |
| Tempo | Chart `tempo` from grafana-community/helm-charts (`*`), one pod, 20 Gi PVC, **14 days**. `https://tempo.microshift.catacumbas69.com`, no authentication, home network only |
| Alloy | Chart `alloy` from grafana/helm-charts (`*`), one pod on meloes (DaemonSet), privileged; its configuration is [`config.alloy`](config.alloy) |
| ArgoCD | Applications `loki`, `tempo`, `alloy` (`microshift/argocd/`) and `observability` (this folder: SCC bindings, Alloy's ConfigMap, the two Routes), all **automated** (prune, selfHeal) |
| Memory | Loki and Tempo ask for 256 Mi, limit 1 Gi (`GOMEMLIMIT=800MiB`); Alloy 128 Mi, limit 512 Mi. CPU requests are small (orch.md decision 85) |

## What reaches Loki

| Stream | Labels | Also |
|---|---|---|
| Every container of every namespace | `namespace`, `pod`, `container`, `app` (the pod's `app.kubernetes.io/name`, else its `app`); Loki adds `service_name` and `stream` (`stdout`/`stderr`) | Our five apps (namespace `catacumbas69-apps`) log ECS JSON (OBS-3): their `level` is a label too, and `trace_id` and `span_id` are structured metadata |
| meloes' journal: MicroShift, CRI-O, sshd, the kernel, every systemd unit | `job="journal"`, `host="meloes"`, `unit` (e.g. `microshift.service`; empty for the kernel), `identifier` (the program), `level` (the priority: `err`, `warning`, `info`, …) | The journal is **volatile** on meloes (`/run/log/journal`, lost at each reboot): Loki is the copy that stays. On its first start Alloy reads the last 12 h |

Two dashboards in Grafana's folder **Logs** do this with menus: **pods · logs** (namespace, app, pod, container,
level, text and trace ID) and **meloes · journal** (unit, program, priority and text); see
[`microshift/apps/monitoring/README.md`](../monitoring/README.md), *Dashboards*. By hand, in Grafana → **Explore** →
Loki:

```
{namespace="catacumbas69-apps", app="cataponte"} | json | level="ERROR"
{job="journal", unit="microshift.service"}
{job="journal", level=~"err|crit|alert|emerg"}
{namespace="pulsar"} |= "Exception"
```

## Traces

The five apps run the **OpenTelemetry Java agent** (init container `otel-agent`, see
[`catacumbas69-apps/README.md`](../catacumbas69-apps/README.md)). Every HTTP request, MongoDB command and Pulsar
message becomes a span; a message carries its trace from one app to the next. The agent sends them over OTLP/HTTP
to Alloy (`alloy.observability.svc.cluster.local:4318`), which batches them to Tempo (gRPC, 4317). **Every**
request is kept (no sampling): the health probes and Prometheus' scrapes are traces too.

In Grafana → **Explore** → Tempo: **Search** by service (`catapulsar`, `cataponte`, …), duration or status. From a
span, **Logs for this span** opens Loki on that app around that time, filtered by the trace's ID; from a log line of
an app, the **Trace** link next to `trace_id` opens the trace.

## One-time setup

1. **DNS**: two records pointing at meloes, like the other Routes: `loki.microshift.catacumbas69.com` and
   `tempo.microshift.catacumbas69.com` (the wildcard certificate already covers them).
2. **Push** the commit: the root Application `microshift` creates `loki`, `tempo`, `alloy` and `observability`,
   which sync on their own. On meloes:
   ```
   oc -n argocd get applications.argoproj.io loki tempo alloy observability
   oc -n observability get pods,pvc
   ```
   Every Application `Synced` and `Healthy`; the pods `loki-0`, `tempo-0` and `alloy-…` `Running`; both PVCs `Bound`.
   Until `observability` has synced, Alloy waits for its ConfigMap and Loki and Tempo for their SCC; they start
   on their own once it has.
3. **Grafana's datasources**: Sync `monitoring` by hand (it is manual): the values add Loki and Tempo. Grafana
   restarts and shows them under **Connections → Data sources**.
4. **The apps**: Sync each of the five Applications by hand; their pods restart with the agent. Check one:
   ```
   oc -n catacumbas69-apps logs deploy/cataponte | grep -m1 opentelemetry-javaagent
   ```

## Troubleshooting

| Symptom | Look at |
|---|---|
| A pod of `observability` is never created | `oc -n observability get events --sort-by=.lastTimestamp`: an SCC refusal means `observability` has not synced yet (`observability-scc.yaml`) |
| Alloy `ContainerCreating` | The ConfigMap `alloy-config` is missing: Sync `observability` |
| Nothing in Loki | Alloy's UI: `oc -n observability port-forward ds/alloy 12345`, then `http://localhost:12345`: every component should be healthy; `loki.write` shows push errors |
| Loki answers `429` to Alloy | Ingestion limits (`limits_config` in `microshift/argocd/loki.yaml`); a burst after a long stop can hit them |
| No traces | The app's log starts with `[otel.javaagent …] opentelemetry-javaagent - version: …`; if not, `JAVA_TOOL_OPTIONS` or the init container. Then Alloy's `otelcol.exporter.otlp.tempo` in its UI |
| An app `OOMKilled` | The agent's memory: raise the Deployment's limit (orch.md decision 104) |
| Apps restart in a loop after starting together (`failed startup probe`, load far above 4) | The agent's start-up work: scale the apps with 2 replicas down to 1 (`oc -n catacumbas69-apps scale deploy/… --replicas=1`), wait for `1/1`, then back to 2 one at a time; keep the instrumentations to the ones used (orch.md decision 105) |
| Old lines refused (`timestamp too old`, `entry too far behind`) | Only on Alloy's first start, when it reads the existing log files from the start: Loki refuses what is older than 7 days. Harmless |
| A chart change does not arrive | Refresh the root Application `microshift` first, then the Application ([`catacumbas69-apps/README.md`](../catacumbas69-apps/README.md), *Where each thing runs*) |
