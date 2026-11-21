#!/usr/bin/env bash
# Dex's secrets on meloes (orch.md decision 98). Runs on the Mac; every oc command goes to meloes over ssh
# (key-based: `ssh meloes oc whoami` must work without a password). Nothing secret ever goes on a command line.
#
#   bash microshift/apps/dex/dex-secret.sh create     once, before the first Sync of dex: asks for your email,
#                                                     generates your password (shown ONCE), the client secrets
#                                                     and the session cookie key, and writes the Secrets
#   bash microshift/apps/dex/dex-secret.sh clients    after a client is added to config.yaml.tmpl and this script:
#                                                     generates the secrets of the new clients only, writes the
#                                                     clients' Secrets, renders the template and restarts Dex
#   bash microshift/apps/dex/dex-secret.sh config     after changing config.yaml.tmpl: renders it again into
#                                                     dex/dex-config and restarts Dex
#   bash microshift/apps/dex/dex-secret.sh password   a new random password (shown ONCE); the old one stops working
#   bash microshift/apps/dex/dex-secret.sh email      changes the email of the user
#
# Secrets it writes:
#   dex/dex-values                     what the template needs: adminEmail, adminHash (bcrypt), the client
#                                      secrets and the cookie key (the password itself is never stored)
#   dex/dex-config                     config.yaml, the rendered template, mounted by Dex
#   catacumbas69-apps/catapulsar-oidc  client-secret, read by Catapulsar
#   monitoring/grafana-oidc            client-secret, read by Grafana
#   argocd/argocd-oidc                 clientSecret, read by ArgoCD through argocd-cm ($argocd-oidc:clientSecret)
#   catacumbas69-apps/catasonarr-oidc  client-secret, read by Catasonarr (orch.md decision 109)
set -euo pipefail

MELOES="${MELOES:-meloes}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$HERE/config.yaml.tmpl"
KEYS=(adminEmail adminHash catapulsarSecret grafanaSecret argocdSecret catasonarrSecret cookieKey)
# The clients' secrets: 'clients' generates the ones dex/dex-values does not have yet (a client added later)
CLIENT_KEYS=(catapulsarSecret grafanaSecret argocdSecret catasonarrSecret)

on_meloes() { ssh -o BatchMode=yes "$MELOES" "$@"; }

random() {  # $1 characters of [A-Za-z0-9]; a fixed amount of random bytes, so no command is cut short by pipefail
  local p
  p="$(head -c 1024 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')"
  p="${p:0:$1}"
  [[ ${#p} -eq $1 ]] || { echo "Could not generate a random value; nothing changed." >&2; exit 1; }
  printf '%s' "$p"
}

bcrypt() {  # password on stdin → bcrypt hash (cost 10, the minimum Dex accepts)
  local h
  h="$(htpasswd -niBC 10 "" | tr -d ':\n')"
  [[ "$h" == \$2* ]] || { echo "htpasswd did not produce a bcrypt hash; nothing changed." >&2; exit 1; }
  printf '%s' "$h"
}

yaml_quote() {  # single-quoted YAML scalar
  printf "'%s'" "${1//\'/\'\'}"
}

apply() {  # a manifest on stdin, applied on meloes
  on_meloes 'oc apply -f -' >/dev/null
}

ensure_namespace() {
  on_meloes "oc create namespace $1 --dry-run=client -o yaml | oc apply -f -" >/dev/null
}

secret_yaml() {  # namespace, name, then key value pairs → a Secret manifest with stringData
  local ns="$1" name="$2"
  shift 2
  printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\n' "$name" "$ns"
  if [[ "$name" == argocd-oidc ]]; then
    printf '  labels:\n    app.kubernetes.io/part-of: argocd   # so that argocd-cm can refer to it\n'
  fi
  printf 'type: Opaque\nstringData:\n'
  while [[ $# -gt 0 ]]; do
    printf '  %s: %s\n' "$1" "$(yaml_quote "$2")"
    shift 2
  done
}

has_values() { on_meloes 'oc -n dex get secret dex-values' >/dev/null 2>&1; }

get_value() {  # key → value from dex/dex-values
  on_meloes "oc -n dex get secret dex-values -o 'jsonpath={.data.$1}'" | openssl base64 -d -A
}

load_values() {  # into v_<key>; bash 3.2 (macOS) has no associative arrays. $1 = new: missing client secrets
  local k name
  for k in "${KEYS[@]}"; do
    name="v_$k"
    printf -v "$name" '%s' "$(get_value "$k")"
    if [[ -z "${!name}" && "${1:-}" == new && " ${CLIENT_KEYS[*]} " == *" $k "* ]]; then
      printf -v "$name" '%s' "$(random 40)"
      echo "Generated the secret of a new client: $k."
    fi
    [[ -n "${!name}" ]] || { echo "dex/dex-values has no $k (a new client? run 'clients'); nothing changed." >&2; exit 1; }
  done
}

write_values() {
  secret_yaml dex dex-values \
    adminEmail "$v_adminEmail" adminHash "$v_adminHash" \
    catapulsarSecret "$v_catapulsarSecret" grafanaSecret "$v_grafanaSecret" argocdSecret "$v_argocdSecret" \
    catasonarrSecret "$v_catasonarrSecret" cookieKey "$v_cookieKey" | apply
}

write_config() {  # renders the template into dex/dex-config and restarts Dex if it runs
  local c
  c="$(cat "$TEMPLATE")"
  c="${c//@ADMIN_EMAIL@/$v_adminEmail}"
  c="${c//@ADMIN_HASH@/$v_adminHash}"
  c="${c//@CATAPULSAR_SECRET@/$v_catapulsarSecret}"
  c="${c//@GRAFANA_SECRET@/$v_grafanaSecret}"
  c="${c//@ARGOCD_SECRET@/$v_argocdSecret}"
  c="${c//@CATASONARR_SECRET@/$v_catasonarrSecret}"
  c="${c//@COOKIE_KEY@/$v_cookieKey}"
  if grep -q '@[A-Z_]*@' <<<"$c"; then
    echo "config.yaml.tmpl has a marker this script does not know; nothing changed." >&2
    exit 1
  fi
  { printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: dex-config\n  namespace: dex\n'
    printf 'type: Opaque\nstringData:\n  config.yaml: |\n'
    printf '    %s\n' "${c//$'\n'/$'\n'    }"   # every line indented under the block scalar
  } | apply
  if on_meloes 'oc -n dex get deploy dex' >/dev/null 2>&1; then
    on_meloes 'oc -n dex rollout restart deploy/dex' >/dev/null
    echo "Dex restarted with the new configuration."
  else
    echo "Dex is not deployed yet: Sync the Application dex in ArgoCD (it syncs on its own after a push)."
  fi
}

write_client_secrets() {
  secret_yaml catacumbas69-apps catapulsar-oidc client-secret "$v_catapulsarSecret" | apply
  secret_yaml monitoring grafana-oidc client-secret "$v_grafanaSecret" | apply
  secret_yaml argocd argocd-oidc clientSecret "$v_argocdSecret" | apply
  secret_yaml catacumbas69-apps catasonarr-oidc client-secret "$v_catasonarrSecret" | apply
}

show_password_once() {
  echo
  echo "Your Dex password (user peewee69). Shown only now: keep it in your password manager."
  echo
  echo "    $1"
  echo
}

ask_email() {
  local e
  read -r -p "Email of the Dex user peewee69: " e
  [[ "$e" == *@*.* && "$e" != *[[:space:]\'\"]* ]] || { echo "That does not look like an email; nothing changed." >&2; exit 1; }
  printf '%s' "$e"
}

command -v htpasswd >/dev/null || { echo "htpasswd not found (it comes with macOS: /usr/sbin/htpasswd)." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found." >&2; exit 1; }
on_meloes 'oc whoami' >/dev/null || { echo "ssh $MELOES 'oc whoami' failed: is the ssh key set up (README.md)?" >&2; exit 1; }

case "${1:-}" in
  create)
    if has_values; then
      echo "dex/dex-values already exists; nothing changed. Use 'config', 'password' or 'email'."
      exit 0
    fi
    v_adminEmail="$(ask_email)"
    password="$(random 24)"
    v_adminHash="$(printf '%s\n' "$password" | bcrypt)"
    v_catapulsarSecret="$(random 40)"
    v_grafanaSecret="$(random 40)"
    v_argocdSecret="$(random 40)"
    v_catasonarrSecret="$(random 40)"
    v_cookieKey="$(random 32)"   # 32 bytes: AES-256
    for ns in dex catacumbas69-apps monitoring argocd; do ensure_namespace "$ns"; done
    write_values
    write_client_secrets
    write_config
    show_password_once "$password"
    ;;
  clients)
    load_values new
    for ns in catacumbas69-apps monitoring argocd; do ensure_namespace "$ns"; done
    write_values
    write_client_secrets
    write_config
    ;;
  config)
    load_values
    write_config
    ;;
  password)
    load_values
    password="$(random 24)"
    v_adminHash="$(printf '%s\n' "$password" | bcrypt)"
    write_values
    write_config
    show_password_once "$password"
    ;;
  email)
    load_values
    v_adminEmail="$(ask_email)"
    write_values
    write_config
    ;;
  *)
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
