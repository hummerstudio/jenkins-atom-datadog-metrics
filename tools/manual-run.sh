#!/usr/bin/env bash
# One manual end-to-end run that prints the bytes that actually left the atom (used while building the atom; the
# repeatable checks live in tools/smoke.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21.0.12.1+1/Contents/Home}"
tmp="$(mktemp -d)"
jar="$(cd dist/datadog-metrics && pwd)/datadog-metrics.jar"
port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
python3 tools/fake_datadog.py "$port" "$tmp/req.jsonl" k-123 &
srv=$!
trap 'kill $srv 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  python3 -c "import socket,sys;socket.create_connection(('127.0.0.1',$port),0.2)" 2>/dev/null && break
  sleep 0.1
done
printf '{"apiUrl":"http://127.0.0.1:%s/api","jobName":"acme/api","buildNumber":"42","buildResult":"SUCCESS","buildUrl":"http://ci/job/acme/api/42/","branchName":"main","agentName":"agent-1","jenkinsUrl":"http://ci/","buildDurationMs":"125000","pauseDurationMs":"5000","queueDurationMs":"3000","tags":"env:prod"}' "$port" >"$tmp/input.json"
printf '{"apiKey":"k-123"}' >"$tmp/.secrets.json"
( cd "$tmp" && atom_data_dir="$tmp" atom_data_input=input.json atom_data_output=output.json atom_workspace="$tmp" \
    "$JAVA_HOME/bin/java" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner 2>/dev/null || true )
kill $srv 2>/dev/null || true
python3 - "$tmp/req.jsonl" "$tmp/output.json" <<'PY'
import json, sys
entries = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8")]
print("requests:", [(e["path"], e["headers"]["DD-API-KEY"]) for e in entries])
print("series body:", json.dumps(json.loads(entries[0]["body"]), indent=1))
print("event body:", json.dumps(json.loads(entries[1]["body"]), indent=1))
out = json.load(open(sys.argv[2], encoding="utf-8"))
print("status:", out["status"], "outputs:", {k: v["value"] for k, v in out["data"].items()
                                             if k in ("seriesCount", "httpStatus", "metricsAccepted")})
PY
