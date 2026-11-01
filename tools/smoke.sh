#!/usr/bin/env bash
# Every check talks to a local fake Datadog that validates the documented wire format itself, so a payload the real
# intake would reject fails here too. Nothing in this script needs the internet.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# the runner merges one step's outputs into the next step's inputs, so an output must never shadow an input
python3 - <<'PY' || fail "an output name collides with an input name in task.json"
import json
spec = json.load(open("task.json", encoding="utf-8"))
inputs = {name.lower(): name for name in spec["input"]}
clash = [name for name in spec["output"] if name.lower() in inputs]
assert not clash, "outputs that shadow inputs: %r" % clash
PY

# the build facts must come from the case, not from whatever this shell happens to export
unset BUILD_NUMBER JOB_NAME BUILD_STATUS BUILD_RESULT BUILD_URL BRANCH_NAME NODE_NAME JENKINS_URL GIT_BRANCH BUILD_USER_ID

key="dd-api-key-for-smoke"
log="$(mktemp)"
ws="$(mktemp -d)"
server=""

free_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

start() {
  [ -n "$server" ] && stop
  port="$(free_port)"
  : >"$log"
  python3 tools/fake_datadog.py "$port" "$log" "$key" "$@" &
  server=$!
  python3 - "$port" <<'PY' || fail "the fake Datadog never started"
import socket, sys, time
port = int(sys.argv[1])
for _ in range(100):
    try:
        s = socket.create_connection(("127.0.0.1", port), 0.2)
        s.close()
        sys.exit(0)
    except OSError:
        time.sleep(0.05)
sys.exit(1)
PY
}

stop() {
  [ -z "$server" ] && return 0
  kill "$server" 2>/dev/null
  wait "$server" 2>/dev/null
  server=""
}

trap 'stop' EXIT

input() { printf '%s' "$1" >"$ws/input.json"; }
secret() { printf '{"apiKey": "%s"}' "$1" >"$ws/.secrets.json"; }
requests() { [ -f "$log" ] && wc -l <"$log" | tr -d ' ' || echo 0; }

# ------------------------------------------------------------------ 1. the v1 payload the plugin sends
start
secret "$key"
input '{"site": "us1", "apiUrl": "http://127.0.0.1:'"$port"'/api",
        "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS",
        "buildUrl": "http://jenkins/job/acme/api/42/", "branchName": "main", "agentName": "agent-1",
        "jenkinsUrl": "http://jenkins/", "buildDurationMs": "125000", "pauseDurationMs": "5000",
        "queueDurationMs": "3000", "tags": "env:test\nteam=platform"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "v1 send: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(requests)" = "2" ] || fail "expected one metrics and one event request, saw $(requests)"
[ "$(output "$ws" seriesCount)" = "4" ] || fail "seriesCount: $(cat "$ws/output.json")"
[ "$(output "$ws" httpStatus)" = "202" ] || fail "httpStatus: $(cat "$ws/output.json")"
[ "$(output "$ws" usedApiVersion)" = "1" ] || fail "usedApiVersion: $(cat "$ws/output.json")"
[ "$(output "$ws" metricsAccepted)" = "true" ] || fail "metricsAccepted: $(cat "$ws/output.json")"

python3 - "$log" "$key" "$(output "$ws" pointTimestampSeconds)" <<'PY' || fail "the v1 request is not the documented shape"
import json, sys
entries = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
key, sent_at = sys.argv[2], int(sys.argv[3])
series, event = entries[0], entries[1]

assert series["path"] == "/api/v1/series", series["path"]
assert series["headers"]["DD-API-KEY"] == key, "the key must travel in DD-API-KEY, not the query string"
assert series["headers"]["Content-Type"].startswith("application/json"), series["headers"]
assert "api_key" not in series["path"], series["path"]

body = json.loads(series["body"])
rows = {row["metric"]: row for row in body["series"]}
assert set(rows) == {"jenkins.job.duration", "jenkins.job.build_duration", "jenkins.job.waiting",
                     "jenkins.job.completed"}, sorted(rows)

duration = rows["jenkins.job.duration"]
assert duration["type"] == "gauge" and duration["points"] == [[sent_at, 125.0]], duration
assert duration["host"] == "agent-1", duration["host"]
# duration minus the 5 s pause, in seconds — not milliseconds
assert rows["jenkins.job.build_duration"]["points"][0][1] == 120.0, rows["jenkins.job.build_duration"]
assert rows["jenkins.job.waiting"]["points"][0][1] == 3.0, rows["jenkins.job.waiting"]
# completed is a counter in the plugin (incrementCounter), so the payload says count with value 1
completed = rows["jenkins.job.completed"]
assert completed["type"] == "count" and completed["points"][0][1] == 1, completed

for name, row in rows.items():
    for tag in ("job:acme/api", "build_number:42", "result:SUCCESS", "branch:main", "node:agent-1",
                "jenkins_url:http://jenkins/", "env:test", "team:platform"):
        assert tag in row["tags"], (name, tag, row["tags"])

assert event["path"] == "/api/v1/events", event["path"]
payload = json.loads(event["body"])
assert payload["title"] == "Job acme/api build #42 success on agent-1", payload["title"]
assert payload["text"].startswith("%%%"), payload["text"]
assert "[Job acme/api build #42](http://jenkins/job/acme/api/42/)" in payload["text"], payload["text"]
assert "finished with status success (2.08 min)" in payload["text"], payload["text"]
assert payload["date_happened"] == sent_at, (payload["date_happened"], sent_at)
assert payload["aggregation_key"] == "acme/api", payload["aggregation_key"]
assert payload["source_type_name"] == "jenkins", payload["source_type_name"]
assert payload["priority"] == "low" and payload["alert_type"] == "success", payload
assert "event_type:default" in payload["tags"], payload["tags"]
assert payload["host"] == "agent-1", payload["host"]
PY

# ------------------------------------------------------------------ 2. v2/series: objects instead of pairs, int types
start
secret "$key"
input '{"apiVersion": "2", "apiUrl": "http://127.0.0.1:'"$port"'/api", "sendEvent": "false",
        "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS", "buildDurationMs": "1000"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "v2 send: $(cat "$ws/output.json") $(cat "$ws/atom.log")"
[ "$(requests)" = "1" ] || fail "sendEvent=false must not post an event, saw $(requests) requests"
[ "$(output "$ws" usedApiVersion)" = "2" ] || fail "usedApiVersion: $(cat "$ws/output.json")"
[ "$(output "$ws" metricsAccepted)" = "true" ] || fail "a 202 with an empty errors array is acceptance: $(cat "$ws/output.json")"

python3 - "$log" <<'PY' || fail "the v2 request is not the documented shape"
import json, sys
entry = json.loads(open(sys.argv[1], encoding="utf-8").read().strip())
assert entry["path"] == "/api/v2/series", entry["path"]
body = json.loads(entry["body"])
row = body["series"][0]
assert row["metric"] == "jenkins.job.duration", row
assert row["type"] == 3, "v2 type is an int: 3 is gauge"
point = row["points"][0]
assert set(point) == {"timestamp", "value"} and isinstance(point["timestamp"], int), point
assert row["resources"] == [{"name": "jenkins", "type": "host"}], row["resources"]
PY

# ------------------------------------------------------------------ 3. 202 with a rejection in the body fails the step
start --refuse-body
secret "$key"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "HTTP 202 with a non-empty errors array must fail the step"
grep -q '"errorCode":40004' "$ws/output.json" || fail "expected 40004: $(cat "$ws/output.json")"
grep -q '"errorType":2' "$ws/output.json" || fail "a remote refusal is THIRD_PART(2): $(cat "$ws/output.json")"
grep -q "the response refused it" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q "metric 'jenkins.job.duration' is invalid" "$ws/output.json" || fail "Datadog's own words must be quoted: $(cat "$ws/output.json")"

# ...and the same answer is survivable when the caller said so
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS", "failOnError": "false"}'
secret "$key"
run "$ws" >"$ws/atom.log" 2>&1 || fail "failOnError=false must not fail the build: $(cat "$ws/output.json")"
[ "$(output "$ws" metricsAccepted)" = "false" ] || fail "the rejection must still be reported: $(cat "$ws/output.json")"
grep -q "WARNING" "$ws/atom.log" || fail "the log must say it was rejected: $(cat "$ws/atom.log")"

# ------------------------------------------------------------------ 4. a refused key is the caller's problem; a 5xx is not
start --deny-key
secret "$key"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "a 403 must fail the step"
grep -q '"errorType":1' "$ws/output.json" || fail "a bad key is USER(1): $(cat "$ws/output.json")"
grep -q '"errorCode":40003' "$ws/output.json" || fail "expected 40003: $(cat "$ws/output.json")"
grep -q "Forbidden" "$ws/output.json" || fail "the reason must be quoted: $(cat "$ws/output.json")"

start --status 503
secret "$key"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "a 503 must fail the step"
grep -q '"errorType":2' "$ws/output.json" || fail "a 5xx is THIRD_PART(2): $(cat "$ws/output.json")"

# ------------------------------------------------------------------ 5. nothing listening is an environment error
stop
secret "$key"
input '{"apiUrl": "http://127.0.0.1:1", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS", "timeoutSeconds": "3"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an unreachable API must fail the step"
grep -q '"errorType":5' "$ws/output.json" || fail "unreachable is ENVIRONMENT(5): $(cat "$ws/output.json")"
grep -q '"errorCode":40005' "$ws/output.json" || fail "expected 40005: $(cat "$ws/output.json")"
grep -q "cannot reach" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# ------------------------------------------------------------------ 6. no key, no request
start
printf '{}' >"$ws/.secrets.json"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api"}'
before="$(requests)"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing apiKey must be refused"
grep -q '"errorCode":40001' "$ws/output.json" || fail "expected 40001: $(cat "$ws/output.json")"
grep -q "apiKey is not configured" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
[ "$(requests)" = "$before" ] || fail "no request may be made without a key"

# ------------------------------------------------------------------ 7. dry run posts nothing and still shows the payload
before="$(requests)"
secret "$key"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "dryRun": "true", "jobName": "acme/api", "buildNumber": "42", "buildResult": "SUCCESS"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "dry run: $(cat "$ws/output.json")"
[ "$(requests)" = "$before" ] || fail "dryRun must not post"
python3 - "$ws/output.json" <<'PY' || fail "the dry-run payload is missing"
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
payload = json.loads(data["metricsPayload"]["value"])
assert payload["series"][0]["metric"] == "jenkins.job.completed", payload
assert json.loads(data["eventPayload"]["value"])["source_type_name"] == "jenkins"
assert data["httpStatus"]["value"] == "" and data["metricsAccepted"]["value"] == "skipped"
PY

# ------------------------------------------------------------------ 8. bad input is named, not guessed at
secret "$key"
input '{"site": "mars", "jobName": "acme/api"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an unknown site must be refused"
grep -q "unknown Datadog site" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "customMetrics": "builds_total=not-a-number"}'
secret "$key"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a malformed custom metric must be refused"
grep -q '"errorCode":40002' "$ws/output.json" || fail "expected 40002: $(cat "$ws/output.json")"
grep -q "has no number" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "tags": "no-value-here"}'
secret "$key"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a malformed tag must be refused"
grep -q "tag must be key:value" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# ------------------------------------------------------------------ 9. the runner's build facts win over the inputs
: >"$log"
printf '%s' '{"number": 7, "job": "acme/web", "result": "FAILURE", "url": "http://ci/job/acme/web/7/",
  "timestamp": 1700000000000, "durationMs": 90000, "causes": ["Started by user tangming"]}' >"$ws/build.json"
secret "$key"
input '{"apiUrl": "http://127.0.0.1:'"$port"'/api", "sendMetrics": "false"}'
run "$ws" atom_build_json=build.json >"$ws/atom.log" 2>&1 || fail "build facts: $(cat "$ws/output.json")"
[ "$(requests)" = "1" ] || fail "sendMetrics=false must post only the event, saw $(requests)"
python3 - "$log" "$ws/output.json" <<'PY' || fail "the event does not carry the build facts"
import json, sys
entry = json.loads(open(sys.argv[1], encoding="utf-8").read().strip())
assert entry["path"] == "/api/v1/events", entry["path"]
event = json.loads(entry["body"])
assert event["title"] == "Job acme/web build #7 failure on jenkins", event["title"]
assert event["date_happened"] == 1700000090, event["date_happened"]      # end time, in seconds
assert event["aggregation_key"] == "acme/web", event["aggregation_key"]
assert event["priority"] == "normal" and event["alert_type"] == "error", event
assert "build_number:7" in event["tags"] and "result:FAILURE" in event["tags"], event["tags"]
assert "Started by user tangming" in event["text"], event["text"]
data = json.load(open(sys.argv[2], encoding="utf-8"))["data"]
assert data["metricsAccepted"]["value"] == "", data["metricsAccepted"]
assert data["eventsAccepted"]["value"] == "true", data["eventsAccepted"]
PY

stop
echo "smoke ok: v1 series+event shape, v2/series shape, 202-with-errors rejection, refused key (403), 503, unreachable, missing key, dry run, bad input, build-context facts"
