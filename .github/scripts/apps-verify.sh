#!/usr/bin/env bash
# mvn verify of the whole reactor in apps/: tests and Checkstyle of every module (orch.md TEST-5, decision 88).
# Used by the `apps` workflow; runs the same anywhere with Java 21 and Maven.
#
#   bash .github/scripts/apps-verify.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../apps"
mvn -B -ntp verify
