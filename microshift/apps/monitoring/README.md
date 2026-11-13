# Monitoring on MicroShift

Prometheus and Grafana on **meloes**, in the namespace `monitoring`, synced by ArgoCD (orch.md decision 90).
They are separate from the kube-prometheus-stack of the K3s cluster (`apps/monitoring/`, Flux), which keeps
`grafana.catacumbas69.com` and `prometheus.catacumbas69.com`.

| | |
|---|---|
| Grafana | `http://grafana.microshift.catacumbas69.com`: anyone can look (Viewer); log in as `admin` to change things |
| Prometheus | `http://prometheus.microshift.catacumbas69.com`: no authentication, home network only |
| Chart | kube-prometheus-stack **93.2.2**, release `prometheus`; values in `kube-prometheus-stack-values.yaml` |
| ArgoCD | Application `monitoring` (`microshift/argocd/monitoring.yaml`): the chart, its values and this folder |
| History | 30 days, at most 17 GB, on a 20 Gi PVC (`topolvm-provisioner`) |
| Grafana data | 1 Gi PVC: what is made in the UI survives restarts |
| Grafana memory | Asks for 256 Mi, limit 1 Gi, `GOMEMLIMIT=800MiB` so Go's garbage collector stops below the limit (512 Mi was too tight for Grafana 12) |

## What it collects

| Target | How | What |
|---|---|---|
| The five apps | `apps-podmonitor.yaml`: every pod labelled `app.kubernetes.io/part-of: catacumbas69`, `/actuator/prometheus` | JVM, HTTP, Pulsar client, MongoDB driver, `catavigia_alerts_open`, `cataponte_*`, `catametricas_*`, `cataudit_*`, `catacommons_*`; label `app` = the app's name |
| Pulsar broker | `pulsar-podmonitor.yaml`: `/metrics/` on port `admin` | Topics, backlog, rates, BookKeeper; `job="broker"` and `kubernetes_pod_name`, as the Pulsar dashboards expect |
| Containers | The chart's kubelet ServiceMonitor (cAdvisor) | CPU, memory, network of every container |
| Pod state | kube-state-metrics | Restarts, Ready, requests and limits |
| meloes | node-exporter (DaemonSet on the host) | CPU, memory, disk, network, temperature (`thermal_zone`, `hwmon`), pressure (PSI) |
| Kubernetes API | The chart's apiserver ServiceMonitor | Requests and latencies of the API server |

Not collected, because MicroShift runs them inside its own process and does not expose them: the controller
manager, the scheduler, etcd and kube-proxy (disabled in the values, with their rules). There is no
Alertmanager: Catavigia raises the alerts (ADR 0015). Prometheus still evaluates the chart's rules, so its
**Alerts** page shows what would fire.

## Dashboards

| Folder | Dashboard | Source |
|---|---|---|
| catacumbas69 | **catacumbas69 · apps**: replicas up, open alerts, bridges, messages, audit, tasks, Pulsar backlog, HTTP, heap, memory against the limit, restarts | `dashboards/catacumbas69.json` (ours) |
| catacumbas69 | **meloes · máquina**: CPU, load, memory, swap, disks, pressure, temperature, network, what is reserved on the node, top containers | `dashboards/meloes.json` (ours) |
| Pulsar | Broker, Topic and Messaging Metrics | `dashboards/pulsar-*.json`, from [streamnative/apache-pulsar-grafana-dashboard](https://github.com/streamnative/apache-pulsar-grafana-dashboard) (`dashboards.kubernetes/`, commit `9a61a26`, Apache 2.0); changed: the `DataSource` variable no longer hides datasources named `Prometheus` (its `regex`), which hid the only one here |
| JVM | JVM (Micrometer), Spring Boot 3.x Statistics | `dashboards/jvm-micrometer.json` and `dashboards/spring-boot-3.json`, from grafana.com: 4701 revision 10 and 19004 as published on 2026-11-11; changed: every `${DS_PROMETHEUS}` (and one fixed uid) points at the datasource `prometheus` |
| General | The chart's Kubernetes, node and Prometheus dashboards | kube-prometheus-stack |

The JSON files are loaded by Grafana's sidecar from the ConfigMaps that `kustomization.yaml` generates (label
`grafana_dashboard`, annotation `grafana_folder`). To change one: edit it in Grafana, **Export → Save to file**,
replace the JSON here, commit and Sync. A dashboard made only in the UI lives in Grafana's PVC, not in git.

## One-time setup

The `oc` commands run on meloes, in the clone of the repository (`git pull` first).

1. **DNS**: two records pointing at meloes, like the other Routes: `grafana.microshift.catacumbas69.com` and
   `prometheus.microshift.catacumbas69.com`.
2. **PSI** (pressure): the kernel needs `psi=1` (done on 2026-11-11 with
   `sudo grubby --update-kernel=ALL --args="psi=1"` and a reboot). Check: `cat /proc/pressure/cpu`.
3. **Grafana's admin password**, before the first Sync:
   ```
   bash microshift/apps/monitoring/grafana-secret.sh
   ```
   It creates the namespace and the Secret `monitoring/grafana-admin` with a random password, never shown.
   See [The admin password](#the-admin-password) to log in or choose your own.
4. **ArgoCD**:
   ```
   oc apply -f microshift/argocd/monitoring.yaml
   ```
   Then **Sync** `monitoring` in the UI. The first Sync installs the operator's CRDs (Server-Side Apply) and
   takes a few minutes; if the PodMonitors fail because their CRD is not there yet, Sync again.
5. **Check**:
   ```
   oc -n monitoring get pods
   ```
   Every pod `Running`: the operator, `prometheus-prometheus-kube-prometheus-prometheus-0`, Grafana,
   kube-state-metrics and the node-exporter. Then, in Prometheus, **Status → Targets**: every target `UP`.

## The admin password

Anyone can look at the dashboards without logging in. To change things, **Sign in** (top right) as `admin`.
On meloes, in the clone:

| Command | What it does |
|---|---|
| `bash microshift/apps/monitoring/grafana-secret.sh show` | Prints the current password, to copy into the login form |
| `bash microshift/apps/monitoring/grafana-secret.sh set` | Asks twice for a password of your own (at least 12 characters; not shown, not kept in the shell history) and makes it the admin's |
| `bash microshift/apps/monitoring/grafana-secret.sh rotate` | The same with a new random password |

Grafana takes the password from the Secret **only the first time it starts**, when it creates its database on
its PVC; after that the database has its own copy. So `set` and `rotate` change it in both places: in the
database, with `grafana cli admin reset-admin-password --password-from-stdin` inside the Grafana pod, and in the
Secret, so that `show` stays right. The password goes through stdin, never on a command line. Grafana must be
running.

**Forgot it?** `set` also works when you no longer know the current one: it does not ask for it.

You can also change it in Grafana (**Profile → Change password**), but then the Secret keeps the old one and
`show` prints a password that no longer works. Prefer `set`.

## Security context constraints

`monitoring-scc.yaml` grants them declaratively, instead of `oc adm policy`:
- `nonroot-v2` to every service account of the namespace: the chart's pods run with fixed non-root UIDs
  (Prometheus 1000, Grafana 472, kube-state-metrics and the operator 65534), which `restricted-v2` refuses;
- `privileged` only to the node-exporter's service account, which needs the host's network, processes and
  filesystems.

## Troubleshooting

| Symptom | Look at |
|---|---|
| A pod stays in `CreateContainerConfigError` or is never created | `oc -n monitoring get events --sort-by=.lastTimestamp`: an SCC refusal names the field it refuses |
| Grafana `CreateContainerConfigError` | The Secret `grafana-admin` is missing: setup step 3 |
| A target `DOWN` in Prometheus | Its error is on the Targets page; for an app, `curl http://<app>.catacumbas69.com/actuator/prometheus` |
| The pressure panels are empty | `cat /proc/pressure/cpu`: no file means `psi=1` is missing |
| Prometheus restarts with `OOMKilled` | Raise `prometheus.prometheusSpec.resources.limits.memory` in the values |
| Grafana stops answering and restarts with exit 137 | Memory near its limit: compare with `grafana.resources.limits.memory`; raise it and `grafana.env.GOMEMLIMIT` (about 80 % of the limit) together |
