package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Datadog metrics atom against a local intake that judges the documented wire format itself, so a payload the real
 * intake would reject fails here too. This is every section of {@code tools/smoke.sh}; {@code tools/fake_datadog.py}
 * stays as the same server in Python.
 */
class DatadogMetricsIT {

    private static final String KEY = "dd-api-key-for-smoke";

    private enum Mode { OK, REFUSE_BODY, DENY_KEY, UNAVAILABLE }

    private static HttpServer server;
    private static int port;
    private static Mode mode = Mode.OK;
    private static final List<Map<String, Object>> REQUESTS = new ArrayList<>();

    private Atom atom;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", DatadogMetricsIT::handle);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ the intake

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String key = exchange.getRequestHeaders().getFirst("DD-API-KEY");
        String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("key", key == null ? "" : key);
        entry.put("contentType", contentType);
        entry.put("body", body);
        synchronized (REQUESTS) {
            REQUESTS.add(entry);
        }

        if (mode == Mode.DENY_KEY || !KEY.equals(key)) {
            reply(exchange, 403, "{\"errors\": [\"Forbidden\"]}");
            return;
        }
        if (!contentType.startsWith("application/json")) {
            reply(exchange, 400, "{\"errors\": [\"Content-Type must be application/json\"]}");
            return;
        }
        Map<String, Object> document;
        try {
            document = Json.parseObject(body);
        } catch (RuntimeException notJson) {
            reply(exchange, 400, "{\"errors\": [\"body is not JSON\"]}");
            return;
        }

        List<String> problems;
        String ok;
        switch (path) {
            case "/api/v1/series" -> {
                problems = seriesV1(document);
                ok = "{\"status\": \"ok\"}";
            }
            case "/api/v2/series" -> {
                problems = seriesV2(document);
                ok = "{\"errors\": []}";
            }
            case "/api/v1/events" -> {
                problems = event(document);
                ok = "{\"event\": {\"id\": 123, \"url\": \"/event/event?id=123\"}, \"status\": \"ok\"}";
            }
            default -> {
                reply(exchange, 404, "{\"errors\": [\"no such endpoint: " + path + "\"]}");
                return;
            }
        }
        if (!problems.isEmpty()) {
            reply(exchange, 400, "{\"errors\": " + Json.write(problems) + "}");
            return;
        }
        if (mode == Mode.UNAVAILABLE) {
            reply(exchange, 503, "{\"errors\": [\"forced status 503\"]}");
            return;
        }
        if (mode == Mode.REFUSE_BODY) {
            reply(exchange, 202, "{\"errors\": [\"metric 'jenkins.job.duration' is invalid: name does not match the "
                    + "allowed pattern ^[a-zA-Z][a-zA-Z0-9_.]*$ for this organization\"]}");
            return;
        }
        reply(exchange, 202, ok);
    }

    private static List<String> seriesV1(Map<String, Object> body) {
        List<String> problems = new ArrayList<>();
        Object rows = body.get("series");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            return List.of("series is missing or empty");
        }
        int index = 0;
        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            String at = "series[" + index++ + "]";
            if (!(row.get("metric") instanceof String metric) || metric.isEmpty()) {
                problems.add(at + ".metric is not a name");
            }
            if (!List.of("gauge", "count", "rate").contains(row.get("type"))) {
                problems.add(at + ".type is " + row.get("type"));
            }
            if (!(row.get("points") instanceof List<?> points) || points.size() != 1) {
                problems.add(at + ".points is not one pair");
                continue;
            }
            if (!(points.get(0) instanceof List<?> point) || point.size() != 2) {
                problems.add(at + ".points[0] is not [timestamp, value]");
                continue;
            }
            if (!(point.get(0) instanceof Integer) && !(point.get(0) instanceof Long)) {
                problems.add(at + " timestamp must be integer seconds, got " + point.get(0));
            }
            if (!(point.get(1) instanceof Number)) {
                problems.add(at + " value is " + point.get(1));
            }
            if (!(row.get("host") instanceof String host) || host.isEmpty()) {
                problems.add(at + ".host is missing");
            }
            if (row.get("tags") != null) {
                if (!(row.get("tags") instanceof List<?> tags)) {
                    problems.add(at + ".tags is not a list of strings");
                } else if (tags.stream().anyMatch(tag -> !(tag instanceof String))) {
                    problems.add(at + ".tags is not a list of strings");
                }
            }
        }
        return problems;
    }

    private static List<String> seriesV2(Map<String, Object> body) {
        List<String> problems = new ArrayList<>();
        Object rows = body.get("series");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            return List.of("series is missing or empty");
        }
        int index = 0;
        for (Object item : list) {
            Map<?, ?> row = (Map<?, ?>) item;
            String at = "series[" + index++ + "]";
            if (!(row.get("metric") instanceof String metric) || metric.isEmpty()) {
                problems.add(at + ".metric is not a name");
            }
            if (!(row.get("type") instanceof Number type) || type.intValue() < 0 || type.intValue() > 3) {
                problems.add(at + ".type must be an int 0..3, got " + row.get("type"));
            }
            if (!(row.get("points") instanceof List<?> points) || points.size() != 1) {
                problems.add(at + ".points is not one point");
                continue;
            }
            if (!(points.get(0) instanceof Map<?, ?> point)
                    || !point.keySet().equals(java.util.Set.of("timestamp", "value"))) {
                problems.add(at + ".points[0] is not {timestamp, value}");
                continue;
            }
            if (!(point.get("timestamp") instanceof Integer) && !(point.get("timestamp") instanceof Long)) {
                problems.add(at + " timestamp must be integer seconds");
            }
            if (!(point.get("value") instanceof Number)) {
                problems.add(at + " value is " + point.get("value"));
            }
            if (!(row.get("resources") instanceof List<?> resources) || resources.isEmpty()) {
                problems.add(at + ".resources is missing");
            } else if (!(((Map<?, ?>) resources.get(0)).get("type") + "").equals("host")
                    || ((Map<?, ?>) resources.get(0)).get("name") == null) {
                problems.add(at + ".resources[0] is not a host resource");
            }
        }
        return problems;
    }

    private static List<String> event(Map<String, Object> body) {
        List<String> problems = new ArrayList<>();
        for (String field : List.of("title", "text", "host", "aggregation_key", "date_happened", "tags",
                "source_type_name", "priority", "alert_type")) {
            if (!body.containsKey(field)) {
                problems.add("event has no " + field);
            }
        }
        if (!(body.get("title") instanceof String title) || title.isEmpty()) {
            problems.add("event title is empty");
        }
        if (!(body.get("date_happened") instanceof Integer) && !(body.get("date_happened") instanceof Long)) {
            problems.add("date_happened must be integer POSIX seconds, got " + body.get("date_happened"));
        }
        if (!List.of("low", "normal").contains(body.get("priority"))) {
            problems.add("priority is " + body.get("priority"));
        }
        if (!List.of("error", "warning", "info", "success").contains(body.get("alert_type"))) {
            problems.add("alert_type is " + body.get("alert_type"));
        }
        if (!"jenkins".equals(body.get("source_type_name"))) {
            problems.add("source_type_name is " + body.get("source_type_name"));
        }
        if (!(body.get("tags") instanceof List<?> tags) || tags.stream().anyMatch(tag -> !(tag instanceof String))) {
            problems.add("tags is not a list of strings");
        }
        return problems;
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ------------------------------------------------------------------ the cases' plumbing

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        mode = Mode.OK;
        synchronized (REQUESTS) {
            REQUESTS.clear();
        }
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/api";
    }

    private Atom withKey(String json) {
        return atom.file(".secrets.json", "{\"apiKey\": \"" + KEY + "\"}").input(json);
    }

    private static List<Map<String, Object>> requests() {
        synchronized (REQUESTS) {
            return new ArrayList<>(REQUESTS);
        }
    }

    private static Map<String, Object> request(int index) {
        return requests().get(index);
    }

    private static Map<String, Object> body(int index) {
        return Json.parseObject(String.valueOf(request(index).get("body")));
    }

    private static List<?> series(Map<String, Object> body) {
        return (List<?>) body.get("series");
    }

    private static Map<String, Map<?, ?>> byMetric(Map<String, Object> body) {
        Map<String, Map<?, ?>> rows = new LinkedHashMap<>();
        for (Object item : series(body)) {
            Map<?, ?> row = (Map<?, ?>) item;
            rows.put(String.valueOf(row.get("metric")), row);
        }
        return rows;
    }

    private static List<String> tags(Map<?, ?> row) {
        List<String> tags = new ArrayList<>();
        for (Object tag : (List<?>) row.get("tags")) {
            tags.add(String.valueOf(tag));
        }
        return tags;
    }

    private static void refused(AtomResult run, String code, String type) {
        run.assertRefused();
        assertEquals("error", run.status(), run.console());
        assertEquals(code, run.field("errorCode"), run.field("message"));
        assertEquals(type, run.field("errorType"), run.field("message"));
    }

    // ------------------------------------------------------------------ 1. the v1 payload

    @Test
    @DisplayName("v1: four series and one event, in the documented shape, and the numbers are seconds not millis")
    void sendsTheV1Payload() throws Exception {
        AtomResult run = withKey("{\"site\": \"us1\", \"apiUrl\": \"" + url() + "\", "
                + "\"jobName\": \"acme/api\", \"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\", "
                + "\"buildUrl\": \"http://jenkins/job/acme/api/42/\", \"branchName\": \"main\", "
                + "\"agentName\": \"agent-1\", \"jenkinsUrl\": \"http://jenkins/\", "
                + "\"buildDurationMs\": \"125000\", \"pauseDurationMs\": \"5000\", \"queueDurationMs\": \"3000\", "
                + "\"tags\": \"env:test\\nteam=platform\"}").run();

        run.assertOk();
        assertEquals(2, requests().size(), "one metrics request and one event request");
        assertEquals("4", run.field("seriesCount"));
        assertEquals("202", run.field("httpStatus"));
        assertEquals("1", run.field("usedApiVersion"));
        assertEquals("true", run.field("metricsAccepted"));

        Map<String, Object> seriesRequest = request(0);
        Map<String, Object> eventRequest = request(1);
        assertEquals("/api/v1/series", seriesRequest.get("path"));
        assertEquals(KEY, seriesRequest.get("key"), "the key must travel in DD-API-KEY, not the query string");
        assertTrue(String.valueOf(seriesRequest.get("contentType")).startsWith("application/json"));
        assertFalse(String.valueOf(seriesRequest.get("path")).contains("api_key"), "no key in the path");

        Map<String, Map<?, ?>> rows = byMetric(body(0));
        assertEquals(List.of("jenkins.job.duration", "jenkins.job.build_duration", "jenkins.job.waiting",
                "jenkins.job.completed"), new ArrayList<>(rows.keySet()));

        long sentAt = Long.parseLong(run.field("pointTimestampSeconds"));
        Map<?, ?> duration = rows.get("jenkins.job.duration");
        assertEquals("gauge", duration.get("type"));
        assertEquals("agent-1", duration.get("host"));
        List<?> point = (List<?>) ((List<?>) duration.get("points")).get(0);
        assertEquals(sentAt, ((Number) point.get(0)).longValue());
        assertEquals(125.0, ((Number) point.get(1)).doubleValue(), 1e-9, "the duration in seconds");
        assertEquals(120.0, value(rows.get("jenkins.job.build_duration")), 1e-9,
                "duration minus the 5 s pause, in seconds — not milliseconds");
        assertEquals(3.0, value(rows.get("jenkins.job.waiting")), 1e-9);
        Map<?, ?> completed = rows.get("jenkins.job.completed");
        assertEquals("count", completed.get("type"), "completed is a counter");
        assertEquals(1.0, value(completed), 1e-9);

        for (Map.Entry<String, Map<?, ?>> row : rows.entrySet()) {
            for (String tag : List.of("job:acme/api", "build_number:42", "result:SUCCESS", "branch:main",
                    "node:agent-1", "jenkins_url:http://jenkins/", "env:test", "team:platform")) {
                assertTrue(tags(row.getValue()).contains(tag), row.getKey() + " is missing " + tag + ": "
                        + tags(row.getValue()));
            }
        }

        assertEquals("/api/v1/events", eventRequest.get("path"));
        Map<String, Object> event = body(1);
        assertEquals("Job acme/api build #42 success on agent-1", event.get("title"));
        assertTrue(String.valueOf(event.get("text")).startsWith("%%%"), String.valueOf(event.get("text")));
        assertTrue(String.valueOf(event.get("text")).contains("[Job acme/api build #42](http://jenkins/job/acme/api/42/)"),
                String.valueOf(event.get("text")));
        assertTrue(String.valueOf(event.get("text")).contains("finished with status success (2.08 min)"),
                String.valueOf(event.get("text")));
        assertEquals(sentAt, ((Number) event.get("date_happened")).longValue());
        assertEquals("acme/api", event.get("aggregation_key"));
        assertEquals("jenkins", event.get("source_type_name"));
        assertEquals("low", event.get("priority"));
        assertEquals("success", event.get("alert_type"));
        assertTrue(tags(event).contains("event_type:default"), tags(event).toString());
        assertEquals("agent-1", event.get("host"));
    }

    @Test
    @DisplayName("v2/series: objects instead of pairs, an int type, and an empty errors array is acceptance")
    void sendsTheV2Payload() throws Exception {
        AtomResult run = withKey("{\"apiVersion\": \"2\", \"apiUrl\": \"" + url() + "\", \"sendEvent\": \"false\", "
                + "\"jobName\": \"acme/api\", \"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\", "
                + "\"buildDurationMs\": \"1000\"}").run();

        run.assertOk();
        assertEquals(1, requests().size(), "sendEvent=false must not post an event");
        assertEquals("2", run.field("usedApiVersion"));
        assertEquals("true", run.field("metricsAccepted"));

        assertEquals("/api/v2/series", request(0).get("path"));
        Map<?, ?> first = (Map<?, ?>) series(body(0)).get(0);
        assertEquals("jenkins.job.duration", first.get("metric"));
        assertEquals(3, ((Number) first.get("type")).intValue(), "v2 type is an int: 3 is gauge");
        Map<?, ?> point = (Map<?, ?>) ((List<?>) first.get("points")).get(0);
        assertEquals(java.util.Set.of("timestamp", "value"), point.keySet());
        assertTrue(point.get("timestamp") instanceof Integer || point.get("timestamp") instanceof Long,
                "v2 timestamp is an int: " + point.get("timestamp"));
        Map<?, ?> resource = (Map<?, ?>) ((List<?>) first.get("resources")).get(0);
        assertEquals("jenkins", resource.get("name"));
        assertEquals("host", resource.get("type"));
    }

    // ------------------------------------------------------------------ 2. refusals that are not HTTP statuses

    @Test
    @DisplayName("a 202 with a rejection in the body fails the step, quoting Datadog's own words")
    void failsOnABodyLevelRejection() throws Exception {
        mode = Mode.REFUSE_BODY;
        AtomResult run = withKey("{\"apiUrl\": \"" + url() + "\", \"jobName\": \"acme/api\", "
                + "\"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\"}").run();

        refused(run, "40004", "2");
        assertTrue(run.field("message").contains("the response refused it"), run.field("message"));
        assertTrue(run.field("message").contains("metric 'jenkins.job.duration' is invalid"),
                "Datadog's own words must be quoted: " + run.field("message"));
    }

    @Test
    @DisplayName("the same rejection is survivable when the caller said so, and still reported")
    void survivesARejectionWhenAskedTo() throws Exception {
        mode = Mode.REFUSE_BODY;
        AtomResult run = withKey("{\"apiUrl\": \"" + url() + "\", \"jobName\": \"acme/api\", "
                + "\"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\", \"failOnError\": \"false\"}").run();

        run.assertOk();
        assertEquals("false", run.field("metricsAccepted"), "the rejection must still be reported");
        assertTrue(run.console().contains("WARNING"), "the log must say it was rejected:\n" + run.console());
    }

    @Test
    @DisplayName("a refused key is the caller's problem; a 5xx is not")
    void separatesUserAndThirdPartyFailures() throws Exception {
        mode = Mode.DENY_KEY;
        AtomResult forbidden = withKey("{\"apiUrl\": \"" + url() + "\", \"jobName\": \"acme/api\", "
                + "\"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\"}").run();
        refused(forbidden, "40003", "1");
        assertTrue(forbidden.field("message").contains("Forbidden"), "the reason must be quoted");

        mode = Mode.UNAVAILABLE;
        AtomResult unavailable = withKey("{\"apiUrl\": \"" + url() + "\", \"jobName\": \"acme/api\", "
                + "\"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\"}").run();
        unavailable.assertRefused();
        assertEquals("2", unavailable.field("errorType"), "a 5xx is THIRD_PART(2)");
    }

    @Test
    @DisplayName("nothing listening is an environment error, not a build failure")
    void reportsAnUnreachableApi() throws Exception {
        AtomResult run = withKey("{\"apiUrl\": \"http://127.0.0.1:1\", \"jobName\": \"acme/api\", "
                + "\"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\", \"timeoutSeconds\": \"3\"}").run();

        refused(run, "40005", "5");
        assertTrue(run.field("message").contains("cannot reach"), run.field("message"));
    }

    @Test
    @DisplayName("no key, no request")
    void refusesWithoutAKey() throws Exception {
        int before = requests().size();
        AtomResult run = atom.file(".secrets.json", "{}")
                .input("{\"apiUrl\": \"" + url() + "\"}").run();

        refused(run, "40001", "1");
        assertTrue(run.field("message").contains("apiKey is not configured"), run.field("message"));
        assertEquals(before, requests().size(), "no request may be made without a key");
    }

    // ------------------------------------------------------------------ 3. dry run and bad input

    @Test
    @DisplayName("a dry run posts nothing and still shows both payloads")
    void postsNothingOnADryRun() throws Exception {
        int before = requests().size();
        AtomResult run = withKey("{\"apiUrl\": \"" + url() + "\", \"dryRun\": \"true\", "
                + "\"jobName\": \"acme/api\", \"buildNumber\": \"42\", \"buildResult\": \"SUCCESS\"}").run();

        run.assertOk();
        assertEquals(before, requests().size(), "dryRun must not post");
        assertEquals("jenkins.job.completed",
                String.valueOf(byMetric(Json.parseObject(run.field("metricsPayload"))).keySet().iterator().next()));
        assertEquals("jenkins",
                Json.parseObject(run.field("eventPayload")).get("source_type_name"));
        assertEquals("", run.field("httpStatus"));
        assertEquals("skipped", run.field("metricsAccepted"));
    }

    @Test
    @DisplayName("bad input is named, not guessed at")
    void namesBadInput() throws Exception {
        AtomResult site = withKey("{\"site\": \"mars\", \"jobName\": \"acme/api\"}").run();
        site.assertRefused();
        assertTrue(site.field("message").contains("unknown Datadog site"), site.field("message"));

        AtomResult metric = withKey("{\"apiUrl\": \"" + url() + "\", \"customMetrics\": "
                + "\"builds_total=not-a-number\"}").run();
        refused(metric, "40002", "1");
        assertTrue(metric.field("message").contains("has no number"), metric.field("message"));

        AtomResult tag = withKey("{\"apiUrl\": \"" + url() + "\", \"tags\": \"no-value-here\"}").run();
        tag.assertRefused();
        assertTrue(tag.field("message").contains("tag must be key:value"), tag.field("message"));
    }

    // ------------------------------------------------------------------ 4. the runner's build facts

    @Test
    @DisplayName("the runner's build facts win over the inputs, and sendMetrics=false posts only the event")
    void usesTheRunnerBuildFacts() throws Exception {
        String build = "{\"number\": 7, \"job\": \"acme/web\", \"result\": \"FAILURE\", "
                + "\"url\": \"http://ci/job/acme/web/7/\", \"timestamp\": 1700000000000, \"durationMs\": 90000, "
                + "\"causes\": [\"Started by user tangming\"]}";
        AtomResult run = atom.file("build.json", build)
                .file(".secrets.json", "{\"apiKey\": \"" + KEY + "\"}")
                .input("atom_build_json", "build.json")
                .input("{\"apiUrl\": \"" + url() + "\", \"sendMetrics\": \"false\"}").run();

        run.assertOk();
        assertEquals(1, requests().size(), "sendMetrics=false must post only the event");
        assertEquals("/api/v1/events", request(0).get("path"));
        Map<String, Object> event = body(0);
        assertEquals("Job acme/web build #7 failure on jenkins", event.get("title"));
        assertEquals(1700000090L, ((Number) event.get("date_happened")).longValue(), "end time, in seconds");
        assertEquals("acme/web", event.get("aggregation_key"));
        assertEquals("normal", event.get("priority"));
        assertEquals("error", event.get("alert_type"));
        assertTrue(tags(event).contains("build_number:7"), tags(event).toString());
        assertTrue(tags(event).contains("result:FAILURE"), tags(event).toString());
        assertTrue(String.valueOf(event.get("text")).contains("Started by user tangming"),
                String.valueOf(event.get("text")));
        assertEquals("", run.field("metricsAccepted"));
        assertEquals("true", run.field("eventsAccepted"));
    }

    @Test
    @DisplayName("no output name shadows an input: the runner merges one step's outputs into the next inputs")
    void taskJsonDoesNotShadowInputs() throws Exception {
        Path module = Path.of(System.getProperty("atom.module", System.getProperty("user.dir")));
        Map<String, Object> spec = Json.parseObject(Files.readString(module.resolve("task.json"),
                StandardCharsets.UTF_8));
        Map<?, ?> inputs = (Map<?, ?>) spec.get("input");
        Map<?, ?> outputs = (Map<?, ?>) spec.get("output");

        List<String> lowered = new ArrayList<>();
        inputs.keySet().forEach(name -> lowered.add(String.valueOf(name).toLowerCase()));
        for (Object name : outputs.keySet()) {
            assertFalse(lowered.contains(String.valueOf(name).toLowerCase()),
                    "output '" + name + "' shadows an input of the same name");
        }
    }

    private static double value(Map<?, ?> row) {
        List<?> point = (List<?>) ((List<?>) row.get("points")).get(0);
        return ((Number) point.get(1)).doubleValue();
    }
}
