# Dex: one login for Catapulsar, Grafana and ArgoCD

Dex (`https://auth.catacumbas69.com`) is the OpenID Connect provider of meloes (orch.md SEC-2, decisions 98–100).
One local user, **`peewee69`**, is the admin of Catapulsar, Grafana and ArgoCD, and one Dex session (30 days, 7 days
without use) signs in to all three: the first app asks for the password, the others let you straight in.

| File | Content |
|---|---|
| `microshift/argocd/dex.yaml` | The Application: the `dex` chart (automated, newest version) and this folder |
| `dex-route.yaml` | The Route `auth.catacumbas69.com` (HTTPS, orch.md decision 97) |
| `config.yaml.tmpl` | Dex's configuration with `@…@` markers for what may not be in git: the user's email, the password hash, the client secrets, the session cookie key |
| `dex-secret.sh` | Runs on the Mac: fills the template into the Secret `dex/dex-config`, writes the client secrets for the three apps, makes the password |

| Client | Redirect URI | Its Secret (key) |
|---|---|---|
| `catapulsar` | `https://catapulsar.catacumbas69.com/login/oauth2/code/dex` | `catacumbas69-apps/catapulsar-oidc` (`client-secret`) |
| `grafana` | `https://grafana.microshift.catacumbas69.com/login/generic_oauth` | `monitoring/grafana-oidc` (`client-secret`) |
| `argocd` | `https://argocd.catacumbas69.com/auth/callback` | `argocd/argocd-oidc` (`clientSecret`, label `app.kubernetes.io/part-of: argocd`) |

## The scripts run on the Mac, over ssh

`dex-secret.sh` and `microshift/apps/tls/ca.sh` run on the Mac and send every `oc` command to meloes with
`ssh meloes`, so the ssh key must let you in without a password (once):

```
ls ~/.ssh/id_ed25519.pub || ssh-keygen -t ed25519 -C necromancer
ssh-copy-id meloes
ssh -o BatchMode=yes meloes 'oc whoami'
```

The last command prints your `oc` user without asking anything. `MELOES=<host>` points the scripts elsewhere.

## One-time setup

After the push that brings `microshift/argocd/dex.yaml` (Dex waits in `ContainerCreating` for its Secret):

```
bash microshift/apps/dex/dex-secret.sh create
```

It asks for the user's email, generates the password, the three client secrets and the cookie key, writes the
Secrets and restarts Dex. **The password is shown once**: keep it in your password manager. Then sign in at
`https://catapulsar.catacumbas69.com`.

| Later | Command |
|---|---|
| Changed `config.yaml.tmpl` | `bash microshift/apps/dex/dex-secret.sh config` |
| A new password (forgotten, or to rotate it) | `bash microshift/apps/dex/dex-secret.sh password` |
| A new email | `bash microshift/apps/dex/dex-secret.sh email` |

The values the template needs are kept in the Secret `dex/dex-values` (the password only as its bcrypt hash).

## How each app signs in

- **Catapulsar**: every page goes to Dex; the top bar shows the user and **Sign out**, which ends Dex's session too.
- **Grafana**: dashboards are still visible without login (Viewer); **Sign in** goes straight to Dex and you are
  Admin. The local `admin` (`grafana-secret.sh`) is still there for emergencies, at
  `https://grafana.microshift.catacumbas69.com/login?disableAutoLogin=true`.
- **ArgoCD**: **Log in via Dex**; you are `role:admin`. The local `admin` stays for emergencies, since Dex itself is
  deployed by ArgoCD. ArgoCD does not check Dex's certificate (orch.md decision 99).

## Troubleshooting

| Symptom | Look at |
|---|---|
| Dex `ContainerCreating` | The Secret `dex/dex-config` is missing: `dex-secret.sh create` |
| "Invalid client" or "Unregistered redirect_uri" in Dex | The client and its redirect URI in `config.yaml.tmpl`; after a change, `dex-secret.sh config` |
| Logged in to Grafana or ArgoCD without admin | The user is `peewee69` (`preferred_username`): Grafana `role_attribute_path`, ArgoCD `argocd-rbac-cm` |
| Each app asks for the password again | `DEX_SESSIONS_ENABLED=true` in `microshift/argocd/dex.yaml`; Dex's logs (`oc -n dex logs deploy/dex`) |
| ArgoCD "too many redirects" after the change | `argocd-server` not restarted after `server.insecure`: `oc -n argocd rollout restart deploy/argocd-server` |
