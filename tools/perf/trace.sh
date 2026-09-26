#!/bin/bash
# Record a Perfetto system trace of Grooveo and print a summary.
#   tools/perf/trace.sh [seconds] [device-serial]
# Do the interaction you want to measure while it records. The .perfetto-trace
# file is kept in tools/perf/traces/ and can be opened at https://ui.perfetto.dev
set -e
SECS=${1:-10}
DEV=${2:-$(adb devices | awk 'NR==2{print $1}')}
PKG=dev.schlubbe.musicagent.standalone
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$HERE/traces"
OUT="$HERE/traces/grooveo-$(date +%Y%m%d-%H%M%S).perfetto-trace"
A="adb -s $DEV"

# Composable names in the trace (androidx.compose.runtime:runtime-tracing).
$A shell am broadcast -a androidx.tracing.perfetto.action.ENABLE_TRACING \
  -n $PKG/androidx.tracing.perfetto.TracingReceiver >/dev/null 2>&1 || true

sed "s/DURATION_MS/$((SECS * 1000))/" "$HERE/config.pbtxt" | \
  $A shell perfetto --txt -c - -o /data/misc/perfetto-traces/grooveo.perfetto-trace >/dev/null
$A pull /data/misc/perfetto-traces/grooveo.perfetto-trace "$OUT" >/dev/null
echo "trace: $OUT"
"$HERE/.venv/bin/python" "$HERE/analyze.py" "$OUT"
