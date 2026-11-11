#!/usr/bin/env bash
# The Grafana admin of meloes (orch.md decision 90): creates Secret monitoring/grafana-admin with a random
# password, which is never shown and never goes to git. Run it once, on meloes, before the first Sync of the
# monitoring Application.
#
#   bash microshift/apps/monitoring/grafana-secret.sh          creates it (does nothing if it already exists)
#   bash microshift/apps/monitoring/grafana-secret.sh show     prints the password, to log in as admin
#   bash microshift/apps/monitoring/grafana-secret.sh rotate   new password; then restart Grafana
set -euo pipefail

NAMESPACE=monitoring
SECRET=grafana-admin

new_password() {
  # A fixed amount of random bytes, so no command of the pipeline is cut short (pipefail and SIGPIPE)
  local p
  p="$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')"
  p="${p:0:32}"
  [[ ${#p} -eq 32 ]] || { echo "Could not generate a password; nothing changed." >&2; exit 1; }
  printf '%s' "$p"
}

case "${1:-create}" in
  create)
    if oc -n "$NAMESPACE" get secret "$SECRET" >/dev/null 2>&1; then
      echo "Secret $NAMESPACE/$SECRET already exists; nothing changed."
      exit 0
    fi
    oc create namespace "$NAMESPACE" --dry-run=client -o yaml | oc apply -f - >/dev/null
    password="$(new_password)"
    oc -n "$NAMESPACE" create secret generic "$SECRET" --from-literal=admin-user=admin \
      --from-literal=admin-password="$password" >/dev/null
    unset password
    echo "Created Secret $NAMESPACE/$SECRET (user admin). Now sync the monitoring Application."
    ;;
  show)
    encoded="$(oc -n "$NAMESPACE" get secret "$SECRET" -o jsonpath='{.data.admin-password}')"
    printf '%s' "$encoded" | base64 --decode
    echo
    ;;
  rotate)
    password="$(new_password)"
    oc -n "$NAMESPACE" create secret generic "$SECRET" --from-literal=admin-user=admin \
      --from-literal=admin-password="$password" --dry-run=client -o yaml | oc apply -f - >/dev/null
    unset password
    echo "New password in Secret $NAMESPACE/$SECRET. Restart Grafana: oc -n $NAMESPACE rollout restart deploy/prometheus-grafana"
    ;;
  *)
    echo "Usage: $0 [create|show|rotate]" >&2
    exit 2
    ;;
esac
