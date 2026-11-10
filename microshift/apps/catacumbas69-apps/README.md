# catacumbas69 apps on MicroShift

The five catacumbas69 apps (Cataponte, Cataudit, Catametricas, Catavigia, Catapulsar) run in MicroShift on
**meloes**, in the namespace `catacumbas69-apps`, synced by ArgoCD. Rules and decisions:
[`apps/orch.md`](../../../apps/orch.md) OPS-1..6 and decisions 81–87.

```
necromancer (Mac)                                meloes (MicroShift)
apps/build/push.sh <app> ── podman push ───────▶ registry (ns registry, Route registry.catacumbas69.com, HTTP)
  writes newTag into <app>/kustomization.yaml               │ CRI-O pulls registry.catacumbas69.com/<app>:<tag>
git commit + push ──▶ GitHub ──▶ ArgoCD (manual Sync) ──▶ ns catacumbas69-apps
                                                              ├── Deployment, Service, Route per app
                                                              └── Secret catacumbas69-apps-mongodb (copied)
```

## Layout

| Folder | Content |
|---|---|
| `microshift/apps/registry/` | The image registry: `registry:2.8.3`, 20 Gi PVC (`topolvm-provisioner`), Route `registry.catacumbas69.com`, HTTP without authentication (home network only) |
| `microshift/apps/catacumbas69-apps/<app>/` | Per app: ConfigMap, Deployment, Service, Route and a `kustomization.yaml` holding the image tag |
| `microshift/apps/mongodb/mongodb-cluster.yaml` | The MongoDB ReplicaSet, with the user `catacumbas69-apps` (read and write on `catacumbas69` only) |
| `microshift/argocd/` | One ArgoCD Application per app and one for the registry. No automated sync: Sync by hand in the UI |
| `apps/build/push.sh` | Builds an image with Podman (linux/amd64), pushes it and writes its tag into the app's `kustomization.yaml` |
| `apps/build/mongo-secret.sh` | Creates the apps' MongoDB password and copies their connection string into `catacumbas69-apps` |

| App | Replicas | Port | Route |
|---|---|---|---|
| Cataponte | 2 | 8082 | `http://cataponte.catacumbas69.com` |
| Cataudit | 2 | 8084 | `http://cataudit.catacumbas69.com` |
| Catametricas | 2 | 8083 | `http://catametricas.catacumbas69.com` |
| Catavigia | 2 | 8085 | `http://catavigia.catacumbas69.com` |
| Catapulsar | 1 | 8081 | `http://catapulsar.catacumbas69.com` |

Every container asks for 25m CPU and 384 Mi and may use up to 1 CPU and 768 Mi (meloes has 4 CPUs, most of
them already requested by MongoDB and Pulsar). Probes are on Actuator
(startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`). The apps reach
each other through their Services (`http://<app>:<port>`), Pulsar through `pulsar.pulsar.svc.cluster.local`, and
MongoDB through the URI in the Secret `catacumbas69-apps-mongodb` (key `uri`), which is never in git.

**Names, never IPs** (orch.md decision 87): the registry and the Routes are reached as `*.catacumbas69.com`, and
Pulsar's external listener (used from IntelliJ) is advertised as `meloes:31650`. When the network changes
(192.168.86.x → 192.168.69.x), only the DNS records change. Like the other Routes, they are plain HTTP.

> **No authentication yet** (orch.md SEC-2): anyone who reaches a Route can use that app. Keep the Routes inside
> the home network.

## One-time setup

The `oc` commands and `apps/build/mongo-secret.sh` run **on meloes**, from the root of a clone of this
repository (`git pull` first), with `oc` pointed at MicroShift (`~/.kube/config` from
`/var/lib/microshift/resources/kubeadmin/kubeconfig`). `apps/build/push.sh` runs **on the Mac** (Podman). If the
scripts are not executable, `chmod +x apps/build/*.sh` once.

### 1. Names (DNS)

`meloes` and `*.catacumbas69.com` must resolve to meloes **from the Mac and from meloes itself**: CRI-O on
meloes pulls from `registry.catacumbas69.com`, which goes through meloes' own router. `meloes` has no domain, so
it resolves only through the search domain the network gives each machine (DHCP) or a hosts entry. Check on the
Mac with `dscacheutil -q host -a name meloes` and on meloes with
`getent hosts registry.catacumbas69.com meloes`.

Pulsar advertises its external listener as `meloes:31650` (`microshift/apps/pulsar/broker-init-script.sh`).
Its ConfigMap keeps the same name, so after syncing `pulsar` in ArgoCD restart the broker once:
`oc -n pulsar rollout restart deploy/pulsar`.

### 2. Let CRI-O pull from the registry over HTTP (on meloes)

```
sudo tee /etc/containers/registries.conf.d/900-catacumbas69.conf >/dev/null <<'EOF'
[[registry]]
location = "registry.catacumbas69.com"
insecure = true
EOF
sudo systemctl restart crio
```

If pods stay in `ErrImagePull` afterwards with an HTTPS error, restart MicroShift too
(`sudo systemctl restart microshift`).

### 3. Deploy the registry

```
oc apply -f microshift/argocd/registry.yaml
```

Sync `registry` in the ArgoCD UI, then check from the Mac: `curl http://registry.catacumbas69.com/v2/` answers
`{}`.

### 4. Install Podman (on the Mac)

```
brew install podman
podman machine init
podman machine start
```

The images are `linux/amd64` (meloes) and the Mac is ARM: the Maven stage runs natively, and the one `RUN` of the
runtime stage runs under Rosetta, which Podman's machine uses by default on Apple silicon. If that step fails
with `exec format error`, recreate the machine (`podman machine rm`, `init`, `start`) with an up-to-date Podman.

### 5. MongoDB user for the apps

```
apps/build/mongo-secret.sh password     # random password in Secret mongodb/catacumbas69-apps-password, never shown
```

Commit and push `microshift/apps/mongodb/mongodb-cluster.yaml` (if not done yet) and Sync `mongodb` in ArgoCD.
Wait until the operator has created the user (the Secret `mongodb/catacumbas69-apps-connection` exists and the
`MongoDBCommunity` is `Running` again: `oc -n mongodb get mdbc`), then:

```
apps/build/mongo-secret.sh copy         # Secret catacumbas69-apps/catacumbas69-apps-mongodb (key uri)
```

The operator cannot keep that Secret in another namespace (it is garbage-collected), so it is copied. Run `copy`
again only if the password changes; then restart the apps (`oc -n catacumbas69-apps rollout restart deploy`).

### 6. First images and ArgoCD Applications

With everything under `apps/` committed:

```
apps/build/push.sh all                  # on the Mac, in start order; writes each newTag
git add microshift/apps/catacumbas69-apps && git commit -m "chore(microshift): deploy <tag>" && git push
# on meloes, after git pull:
oc apply -f microshift/argocd/cataponte.yaml -f microshift/argocd/cataudit.yaml \
  -f microshift/argocd/catametricas.yaml -f microshift/argocd/catavigia.yaml -f microshift/argocd/catapulsar.yaml
```

Sync in the ArgoCD UI in the **start order**: Cataponte → Cataudit → Catametricas → Catavigia → Catapulsar,
each one once the previous is Healthy.

### 7. Reset the URLs in Catapulsar

Catapulsar's settings topic may still hold the `localhost` URLs saved from IntelliJ runs, and they win over the
ConfigMap. Open `http://catapulsar.catacumbas69.com` → Settings → Connections and choose **use default** for
Cataponte, Catametricas, Cataudit and Catavigia (Catavigia also reads the first three from there).

## Every release

```
git commit …                            # the code under apps/ must be committed (OPS-2)
apps/build/push.sh catavigia            # or several apps, or all
git add microshift/apps/catacumbas69-apps && git commit -m "chore(microshift): catavigia <tag>" && git push
```

Then Sync the app in ArgoCD. The tag is `<version>-<short sha>`; the script stops when `apps/` has uncommitted
changes, and `FORCE=1` builds anyway with a `-dirty-<date-time>` tag (for a quick test only).

## Troubleshooting

| Symptom | Look at |
|---|---|
| `ErrImagePull` / `ImagePullBackOff` | Steps 1–2 on meloes; the tag in the app's `kustomization.yaml` was pushed (`curl http://registry.catacumbas69.com/v2/<app>/tags/list`) |
| `CreateContainerConfigError` | The Secret `catacumbas69-apps-mongodb` is missing: step 5 (`copy`) |
| Pod not Ready | `oc -n catacumbas69-apps logs deploy/<app>`; `/actuator/health/readiness` lists what is down (Pulsar, MongoDB) |
| Catapulsar panels say an app does not answer | Step 7 |
| `podman push` fails with a timeout | The Route waits 5 min (`haproxy.router.openshift.io/timeout`); raise it in `registry-route.yaml` |
| Registry full | Delete old tags through the registry API, then `registry garbage-collect /etc/docker/registry/config.yml` in its pod |
