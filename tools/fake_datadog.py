#!/usr/bin/env python3
"""A Datadog intake that judges the wire format instead of trusting the sender.

    tools/fake_datadog.py <port> <log.jsonl> <expected-api-key> [--refuse-body] [--deny-key] [--status N]

Every request is appended to <log.jsonl> as one JSON object (method, path, headers, body), so the smoke script can
assert the payload itself rather than the atom's own report of it. On top of that this server refuses what Datadog
would refuse — a missing DD-API-KEY, a body that is not the documented shape — which means a wrong payload fails the
smoke even if the smoke's own assertions were forgetful.

  --refuse-body   answer 202 with a non-empty {"errors": [...]}: the case a status-code-only sender calls success
  --deny-key      answer 403 {"errors": ["Forbidden"]}, as an invalid API key does
"""
import http.server
import json
import sys

port = int(sys.argv[1])
log_path = sys.argv[2]
expected_key = sys.argv[3]
flags = sys.argv[4:]
refuse_body = "--refuse-body" in flags
deny_key = "--deny-key" in flags
forced_status = None
if "--status" in flags:
    forced_status = int(flags[flags.index("--status") + 1])


def series_rows_v1(body):
    """The v1 contract: series[i] = {metric, points: [[ts, value]], type, host}. Returns problems."""
    problems = []
    rows = body.get("series")
    if not isinstance(rows, list) or not rows:
        return ["series is missing or empty"]
    for i, row in enumerate(rows):
        if not isinstance(row.get("metric"), str) or not row["metric"]:
            problems.append("series[%d].metric is not a name" % i)
        if row.get("type") not in ("gauge", "count", "rate"):
            problems.append("series[%d].type is %r" % (i, row.get("type")))
        points = row.get("points")
        if not isinstance(points, list) or len(points) != 1:
            problems.append("series[%d].points is not one pair" % i)
            continue
        point = points[0]
        if not isinstance(point, list) or len(point) != 2:
            problems.append("series[%d].points[0] is not [timestamp, value]" % i)
            continue
        if not isinstance(point[0], int):
            problems.append("series[%d] timestamp must be integer seconds, got %r" % (i, point[0]))
        if isinstance(point[1], bool) or not isinstance(point[1], (int, float)):
            problems.append("series[%d] value is %r" % (i, point[1]))
        if not isinstance(row.get("host"), str) or not row["host"]:
            problems.append("series[%d].host is missing" % i)
        tags = row.get("tags")
        if tags is not None and (not isinstance(tags, list) or any(not isinstance(t, str) for t in tags)):
            problems.append("series[%d].tags is not a list of strings" % i)
    return problems


def series_rows_v2(body):
    """The v2 contract: points are objects, type is an int, the host lives in resources."""
    problems = []
    rows = body.get("series")
    if not isinstance(rows, list) or not rows:
        return ["series is missing or empty"]
    for i, row in enumerate(rows):
        if not isinstance(row.get("metric"), str) or not row["metric"]:
            problems.append("series[%d].metric is not a name" % i)
        if row.get("type") not in (0, 1, 2, 3):
            problems.append("series[%d].type must be an int 0..3, got %r" % (i, row.get("type")))
        points = row.get("points")
        if not isinstance(points, list) or len(points) != 1:
            problems.append("series[%d].points is not one point" % i)
            continue
        point = points[0]
        if not isinstance(point, dict) or set(point) != {"timestamp", "value"}:
            problems.append("series[%d].points[0] is not {timestamp, value}" % i)
            continue
        if not isinstance(point["timestamp"], int):
            problems.append("series[%d] timestamp must be integer seconds" % i)
        if isinstance(point["value"], bool) or not isinstance(point["value"], (int, float)):
            problems.append("series[%d] value is %r" % (i, point["value"]))
        resources = row.get("resources")
        if not isinstance(resources, list) or not resources:
            problems.append("series[%d].resources is missing" % i)
        elif resources[0].get("type") != "host" or not resources[0].get("name"):
            problems.append("series[%d].resources[0] is not a host resource" % i)
    return problems


def event_problems(body):
    """The v1 event contract, field by field, as DatadogApiClient#event builds it."""
    problems = []
    for field in ("title", "text", "host", "aggregation_key", "date_happened", "tags",
                  "source_type_name", "priority", "alert_type"):
        if field not in body:
            problems.append("event has no %s" % field)
    if not isinstance(body.get("title"), str) or not body.get("title"):
        problems.append("event title is empty")
    if not isinstance(body.get("date_happened"), int):
        problems.append("date_happened must be integer POSIX seconds, got %r" % body.get("date_happened"))
    if body.get("priority") not in ("low", "normal"):
        problems.append("priority is %r" % body.get("priority"))
    if body.get("alert_type") not in ("error", "warning", "info", "success"):
        problems.append("alert_type is %r" % body.get("alert_type"))
    if body.get("source_type_name") != "jenkins":
        problems.append("source_type_name is %r" % body.get("source_type_name"))
    if not isinstance(body.get("tags"), list) or any(not isinstance(t, str) for t in body["tags"]):
        problems.append("tags is not a list of strings")
    return problems


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _answer(self, status, doc):
        body = json.dumps(doc).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        entry = {
            "method": "POST",
            "path": self.path,
            "headers": {
                "DD-API-KEY": self.headers.get("DD-API-KEY", ""),
                "Content-Type": self.headers.get("Content-Type", ""),
                "User-Agent": self.headers.get("User-Agent", ""),
            },
            "body": raw.decode("utf-8", "replace"),
        }
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps(entry) + "\n")

        if deny_key or entry["headers"]["DD-API-KEY"] != expected_key:
            self._answer(403, {"errors": ["Forbidden"]})
            return
        if not entry["headers"]["Content-Type"].startswith("application/json"):
            self._answer(400, {"errors": ["Content-Type must be application/json"]})
            return
        try:
            doc = json.loads(raw)
        except ValueError:
            self._answer(400, {"errors": ["body is not JSON"]})
            return

        if self.path == "/api/v1/series":
            problems = series_rows_v1(doc)
            ok = {"status": "ok"}
        elif self.path == "/api/v2/series":
            problems = series_rows_v2(doc)
            ok = {"errors": []}
        elif self.path == "/api/v1/events":
            problems = event_problems(doc)
            ok = {"event": {"id": 123, "url": "/event/event?id=123"}, "status": "ok"}
        else:
            self._answer(404, {"errors": ["no such endpoint: " + self.path]})
            return

        if problems:
            self._answer(400, {"errors": problems})
            return
        if forced_status is not None:
            self._answer(forced_status, {"errors": ["forced status %d" % forced_status]})
            return
        if refuse_body:
            # 202 Accepted with a rejection in the body: exactly what a status-code-only sender misreads as success
            self._answer(202, {"errors": ["metric 'jenkins.job.duration' is invalid: name does not match the "
                                          "allowed pattern ^[a-zA-Z][a-zA-Z0-9_.]*$ for this organization"]})
            return
        self._answer(202, ok)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
