#!/usr/bin/env bash
# Builds an app's image for MicroShift (linux/amd64) with Podman, pushes it to the registry on meloes and writes
# its tag into the app's manifest (orch.md OPS-1..3, decisions 81–86). Then commit, push and Sync in ArgoCD.
#
#   apps/build/push.sh catavigia     one app
#   apps/build/push.sh all           every app, in start order
#
# The tag is <version>-<short sha> (OPS-2), so it always points at committed code: with uncommitted changes
# under apps/ the script stops; FORCE=1 builds anyway with the tag <version>-<sha>-dirty-<date-time>.
# Environment: REGISTRY (default registry.catacumbas69.com), FORCE=1.
set -euo pipefail

APPS=(cataponte cataudit catametricas catavigia catapulsar)
REGISTRY="${REGISTRY:-registry.catacumbas69.com}"
APPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(git -C "$APPS_DIR" rev-parse --show-toplevel)"
MANIFESTS="$REPO_DIR/microshift/apps/catacumbas69-apps"

usage() {
  echo "Usage: $0 <app|all>   apps: ${APPS[*]}" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
if [[ "$1" == "all" ]]; then
  targets=("${APPS[@]}")
elif [[ " ${APPS[*]} " == *" $1 "* ]]; then
  targets=("$1")
else
  usage
fi

command -v podman >/dev/null || { echo "podman is not installed (see microshift/apps/catacumbas69-apps/README.md)" >&2; exit 1; }
command -v xmllint >/dev/null || { echo "xmllint is missing (it comes with macOS)" >&2; exit 1; }

sha="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
suffix=""
if [[ -n "$(git -C "$REPO_DIR" status --porcelain -- apps)" ]]; then
  if [[ "${FORCE:-}" != "1" ]]; then
    echo "Uncommitted changes under apps/: commit them first, or run with FORCE=1 for a -dirty image." >&2
    exit 1
  fi
  suffix="-dirty-$(date +%Y%m%d%H%M%S)"
fi

for app in "${targets[@]}"; do
  version="$(xmllint --xpath "/*[local-name()='project']/*[local-name()='version']/text()" "$APPS_DIR/$app/pom.xml")"
  tag="${version}-${sha}${suffix}"
  image="$REGISTRY/$app:$tag"
  manifest="$MANIFESTS/$app/kustomization.yaml"
  [[ -f "$manifest" ]] || { echo "No manifest at $manifest" >&2; exit 1; }

  echo "── $app → $image"
  podman build --platform linux/amd64 -f "$APPS_DIR/$app/Dockerfile" -t "$image" "$APPS_DIR"
  # The registry is plain HTTP through its Route, inside the home network (decision 82)
  podman push --tls-verify=false "$image"
  # The manifest has one image; its newTag becomes the tag just pushed
  TAG="$tag" perl -pi -e 's/^(\s*newTag:).*/$1 "$ENV{TAG}"/' "$manifest"
  echo "   $manifest → newTag: \"$tag\""
done

echo
echo "Done. Next: git add microshift/apps/catacumbas69-apps && git commit && git push, then Sync in ArgoCD."
