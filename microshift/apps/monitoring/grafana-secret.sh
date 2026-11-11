#!/usr/bin/env bash
# The Grafana admin of meloes (orch.md decision 90). Runs on meloes, with oc pointed at MicroShift.
#
#   bash microshift/apps/monitoring/grafana-secret.sh          creates Secret monitoring/grafana-admin with a random
#                                                               password (once, before the first Sync)
#   bash microshift/apps/monitoring/grafana-secret.sh show     prints the password in the Secret
#   bash microshift/apps/monitoring/grafana-secret.sh set      asks for a password of your own (not shown, not in
#                                                               the shell history) and makes it the admin's
#   bash microshift/apps/monitoring/grafana-secret.sh rotate   the same with a new random password
#
# Grafana reads the Secret only the first time it starts, when it creates its database (on its PVC). So `set` and
# `rotate` change the password in both places: in Grafana's database, with `grafana cli` inside the pod, and in
# the Secret, so that `show` stays right. The password never goes on a command line.
set -euo pipefail

NAMESPACE=monitoring
SECRET=grafana-admin
DEPLOYMENT=deploy/prometheus-grafana

random_password() {
  # A fixed amount of random bytes, so no command of the pipeline is cut short (pipefail and SIGPIPE)
  local p
  p="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')"
  p="${p:0:32}"
  [[ ${#p} -eq 32 ]] || { echo "Could not generate a password; nothing changed." >&2; exit 1; }
  printf '%s' "$p"
}

write_secret() {  # password on stdin
  oc -n "$NAMESPACE" create secret generic "$SECRET" --from-literal=admin-user=admin \
    --from-file=admin-password=/dev/stdin --dry-run=client -o yaml | oc apply -f - >/dev/null
}

set_in_grafana() {  # password on stdin; the image is distroless (no shell), so the binary is named directly
  local input
  input="$(cat)"
  for bin in grafana /usr/share/grafana/bin/grafana; do
    if printf '%s\n' "$input" | oc -n "$NAMESPACE" exec -i "$DEPLOYMENT" -c grafana -- \
        "$bin" cli admin reset-admin-password --password-from-stdin >/dev/null 2>&1; then
      return 0
    fi
  done
  echo "Could not run 'grafana cli' in the Grafana pod; nothing changed. Is Grafana running?" >&2
  echo "  oc -n $NAMESPACE get pods -l app.kubernetes.io/name=grafana" >&2
  exit 1
}

change() {  # password as $1, kept out of the process list
  printf '%s' "$1" | set_in_grafana
  printf '%s' "$1" | write_secret
}

case "${1:-create}" in
  create)
    if oc -n "$NAMESPACE" get secret "$SECRET" >/dev/null 2>&1; then
      echo "Secret $NAMESPACE/$SECRET already exists; nothing changed."
      exit 0
    fi
    oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f - >/dev/null
    random_password | write_secret
    echo "Created Secret $NAMESPACE/$SECRET (user admin). Now sync the monitoring Application."
    ;;
  show)
    encoded="$(oc -n "$NAMESPACE" get secret "$SECRET" -o jsonpath='{.data.admin-password}')"
    printf '%s' "$encoded" | base64 --decode
    echo
    ;;
  set)
    read -r -s -p "New admin password: " first
    echo
    read -r -s -p "Again: " second
    echo
    if [[ "$first" != "$second" ]]; then
      echo "They differ; nothing changed." >&2
      exit 1
    fi
    if [[ ${#first} -lt 12 ]]; then
      echo "Use at least 12 characters; nothing changed." >&2
      exit 1
    fi
    change "$first"
    unset first second
    echo "Admin password changed in Grafana and in Secret $NAMESPACE/$SECRET."
    ;;
  rotate)
    password="$(random_password)"
    change "$password"
    unset password
    echo "New random admin password in Grafana and in Secret $NAMESPACE/$SECRET (see it with: $0 show)."
    ;;
  *)
    echo "Usage: $0 [create|show|set|rotate]" >&2
    exit 2
    ;;
esac
