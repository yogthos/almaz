#!/bin/bash
# CI's jolt-metrics gate (test/almaz/metrics_gate.clj) over a JSON report:
#   native/tools/metrics_gate.sh report.json [--update]
# Runs from outside the project so jolt doesn't load the app's native
# libraries, which the gate doesn't need.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
report="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
shift
update=""
if [ "${1:-}" = "--update" ]; then update=' "--update"'; fi
cd "${TMPDIR:-/tmp}"
exec jolt -e "(load-file \"$root/test/almaz/metrics_gate.clj\")
(almaz.metrics-gate/-main \"$report\" \"--baseline\" \"$root/.jolt-metrics-baseline.edn\"$update)"
