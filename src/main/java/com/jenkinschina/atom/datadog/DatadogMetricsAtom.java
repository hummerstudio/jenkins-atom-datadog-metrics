package com.jenkinschina.atom.datadog;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.BuildInfo;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends this build's metrics and one event to Datadog over its HTTP API.
 *
 * <p>The payloads are the ones the official {@code datadog} Jenkins plugin (10.0.0) puts on the wire: it posts
 * {@code {"series":[{"metric","points","type","host","tags"}]}} to {@code v1/series} and
 * {@code {"title","text","host","aggregation_key","date_happened","tags","source_type_name","priority","alert_type"}}
 * to {@code v1/events}, authenticating with the {@code DD-API-KEY} header. Those shapes were read out of the plugin's
 * own bytecode, not from memory; {@code apiVersion=2} additionally speaks {@code v2/series}, whose points are objects
 * and whose type is an int.
 *
 * <p>The one rule this atom adds is the one a status code cannot express: a 2xx whose body carries a non-empty
 * {@code errors} array (or a {@code status} that is not {@code ok}) is a rejection, and it fails the step.
 */
public final class DatadogMetricsAtom implements AtomTask {

    /** Short site aliases → the API host Datadog documents for them. */
    private static final Map<String, String> SITES = new LinkedHashMap<>();

    static {
        SITES.put("us1", "api.datadoghq.com");
        SITES.put("datadoghq.com", "api.datadoghq.com");
        SITES.put("us3", "api.us3.datadoghq.com");
        SITES.put("us5", "api.us5.datadoghq.com");
        SITES.put("eu", "api.datadoghq.eu");
        SITES.put("datadoghq.eu", "api.datadoghq.eu");
        SITES.put("ap1", "api.ap1.datadoghq.com");
        SITES.put("ap2", "api.ap2.datadoghq.com");
        SITES.put("uk1", "api.uk1.datadoghq.com");
        SITES.put("gov", "api.ddog-gov.com");
        SITES.put("ddog-gov.com", "api.ddog-gov.com");
        SITES.put("us2.ddog-gov.com", "api.us2.ddog-gov.com");
    }

    /** Datadog's accepted v1 series types; v2 sends these as ints. */
    private static final Map<String, Integer> V2_TYPES = Map.of("gauge", 3, "count", 1, "rate", 2);

    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String apiKey = trimmed(context.getSecret("apiKey"));
        if (apiKey.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "apiKey is not configured: bind a Datadog API key credential to this atom's apiKey secret"
                            + " (x-jenkins.secrets), the key is never taken from a plain input");
        }

        String site = param(context, "site", "datadoghq.com");
        String apiUrl = param(context, "apiUrl", "");
        String base;
        try {
            base = apiBase(site, apiUrl);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40002, e.getMessage());
        }

        Facts facts;
        try {
            facts = Facts.of(context);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40002, e.getMessage());
        }

        boolean dryRun = context.getBooleanParam("dryRun", false);
        boolean sendMetrics = context.getBooleanParam("sendMetrics", true);
        boolean sendEvent = context.getBooleanParam("sendEvent", true);
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int timeoutSeconds = Math.max(1, context.getIntParam("timeoutSeconds", 30));
        long timestamp = facts.timestampSeconds;

        List<String> tags;
        List<Series> series;
        List<String> custom;
        try {
            tags = tags(facts, param(context, "tags", ""));
            custom = parseMetrics(param(context, "customMetrics", ""));
            series = series(facts, param(context, "metricPrefix", "jenkins.job"), custom, timestamp, sendMetrics);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40002, e.getMessage());
        }

        boolean v2 = "2".equals(param(context, "apiVersion", "auto"));
        String metricsPath = v2 ? "/v2/series" : "/v1/series";
        String eventPath = "/v1/events";

        Map<String, Object> metricsPayload = v2
                ? v2Payload(series, facts.host, tags)
                : v1Payload(series, facts.host, tags);
        Map<String, Object> eventPayload = eventPayload(context, facts, tags);

        String metricsBody = Json.write(metricsPayload);
        String eventBody = Json.write(eventPayload);

        int metricsBytes = metricsBody.getBytes(StandardCharsets.UTF_8).length;
        context.log("[datadog] " + facts.job + " #" + facts.number + " " + facts.result
                + " -> " + base + metricsPath + " (" + series.size() + " series, " + metricsBytes + " bytes)"
                + (dryRun ? " [dry run: nothing is sent]" : ""));

        Attempt metricsAttempt = Attempt.skipped();
        Attempt eventAttempt = Attempt.skipped();

        if (!dryRun) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            try {
                if (!series.isEmpty()) {
                    metricsAttempt = post(client, base + metricsPath, apiKey, metricsBody, timeoutSeconds, context);
                    String refusal = refusal(metricsAttempt.status, metricsAttempt.body);
                    if (refusal != null) {
                        return refused(metricsAttempt.status, refusal, "metrics", metricsAttempt.endpoint,
                                failOnError, context);
                    }
                } else {
                    context.log("[datadog] sendMetrics=false or nothing to report: no series posted");
                }
                if (sendEvent) {
                    eventAttempt = post(client, base + eventPath, apiKey, eventBody, timeoutSeconds, context);
                    String refusal = refusal(eventAttempt.status, eventAttempt.body);
                    if (refusal != null) {
                        return refused(eventAttempt.status, refusal, "event", eventAttempt.endpoint,
                                failOnError, context);
                    }
                }
            } catch (TransportFailure failure) {
                // nothing was decided by Datadog, so this is the environment, not a verdict on the payload
                return AtomResult.error(ErrorType.ENVIRONMENT, 40005,
                        "cannot reach " + failure.endpoint + " (" + failure.durationMs + " ms): "
                                + failure.getMessage()
                                + " — check the runner's network, proxy and Datadog site");
            }
        }

        context.log("[datadog] metrics " + metricsAttempt.describe() + ", event " + eventAttempt.describe());

        String summary = (dryRun ? "dry run: built " : "sent ") + series.size() + " series and "
                + (sendEvent ? 1 : 0) + " event for " + base;
        return AtomResult.success(summary)
                .data("usedSite", site)
                .data("usedApiVersion", v2 ? "2" : "1")
                .data("metricsEndpoint", series.isEmpty() ? "" : base + metricsPath)
                .data("eventEndpoint", sendEvent ? base + eventPath : "")
                .data("httpStatus", metricsAttempt.statusText())
                .data("eventHttpStatus", eventAttempt.statusText())
                .data("metricsAccepted", metricsAttempt.verdict(series.isEmpty()))
                .data("eventsAccepted", eventAttempt.verdict(!sendEvent))
                .data("seriesCount", String.valueOf(series.size()))
                .data("pointTimestampSeconds", String.valueOf(timestamp))
                .data("tagsUsed", String.join(",", tags))
                .data("metricsPayload", metricsBody)
                .data("eventPayload", eventBody);
    }

    // ------------------------------------------------------------------ payloads

    /** {@code {"series":[{"metric","points":[[t,v]],"type","host","tags"}]}} — the plugin's v1 body. */
    static Map<String, Object> v1Payload(List<Series> series, String host, List<String> tags) {
        List<Object> rows = new ArrayList<>();
        for (Series one : series) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", one.metric);
            row.put("points", List.of(List.of(one.timestamp, one.value)));
            row.put("type", one.type);
            row.put("host", host);
            if (!tags.isEmpty()) {
                row.put("tags", new ArrayList<Object>(tags));
            }
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("series", rows);
        return payload;
    }

    /** {@code {"series":[{"metric","type":3,"points":[{"timestamp","value"}],"resources":[{"name","type":"host"}],"tags"}]}}. */
    static Map<String, Object> v2Payload(List<Series> series, String host, List<String> tags) {
        List<Object> rows = new ArrayList<>();
        for (Series one : series) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", one.timestamp);
            point.put("value", one.value);

            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("name", host);
            resource.put("type", "host");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", one.metric);
            row.put("type", V2_TYPES.getOrDefault(one.type, 3));
            row.put("points", List.of(point));
            row.put("resources", List.of(resource));
            if (!tags.isEmpty()) {
                row.put("tags", new ArrayList<Object>(tags));
            }
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("series", rows);
        return payload;
    }

    /** The v1 event body the plugin builds in {@code DatadogApiClient#event}. */
    Map<String, Object> eventPayload(AtomContext context, Facts facts, List<String> tags) {
        String alertType = param(context, "alertType", "auto").toLowerCase(Locale.ROOT);
        if ("auto".equals(alertType)) {
            // the plugin: SUCCESS -> success, FAILURE -> error, anything else (UNSTABLE, ABORTED, ...) -> warning
            alertType = switch (facts.result) {
                case "SUCCESS" -> "success";
                case "FAILURE" -> "error";
                default -> "warning";
            };
        }
        String priority = param(context, "priority", "auto").toLowerCase(Locale.ROOT);
        if ("auto".equals(priority)) {
            // the plugin: a successful build is low priority, everything else normal
            priority = "SUCCESS".equals(facts.result) ? "low" : "normal";
        }

        String title = param(context, "eventTitle", "");
        if (title.isEmpty()) {
            // "Job %s build #%s %s on %s" — BuildFinishedEventImpl's concat recipe, verbatim
            title = "Job " + facts.job + " build #" + facts.number + " " + facts.result.toLowerCase(Locale.ROOT)
                    + " on " + facts.host;
        }
        String text = param(context, "eventText", "");
        if (text.isEmpty()) {
            text = "%%% \n[Job " + facts.job + " build #" + facts.number + "](" + facts.url
                    + ") finished with status " + facts.result.toLowerCase(Locale.ROOT) + " "
                    + facts.formattedDuration() + (facts.causes.isEmpty() ? "" : "\n" + facts.causes)
                    + " \n%%%";
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("title", title);
        event.put("text", text);
        event.put("host", facts.host);
        event.put("aggregation_key", param(context, "aggregationKey", facts.job));
        event.put("date_happened", facts.timestampSeconds);
        event.put("tags", new ArrayList<Object>(tags));
        // the plugin pins this string; it is what makes the event show up with the Jenkins source
        event.put("source_type_name", "jenkins");
        event.put("priority", priority);
        event.put("alert_type", alertType);
        return event;
    }

    // ------------------------------------------------------------------ the verdict

    /**
     * Why Datadog refused this response, or {@code null} when it accepted it.
     *
     * <p>A 2xx is not acceptance: the intake answers {@code 202 {"status":"ok"}} / {@code 202 {"errors":[]}} and puts
     * its reasons in {@code errors} — and a proxy in front of it can return 200 with the same body. An empty array is
     * fine, a non-empty one is the refusal, and that distinction is the whole point of this method.
     */
    static String refusal(int status, String body) {
        String text = body == null ? "" : body.strip();
        boolean ok = status >= 200 && status < 300;
        String detail = errorDetail(text);

        if (!ok) {
            if (!detail.isEmpty()) {
                return "HTTP " + status + ": " + detail;
            }
            return "HTTP " + status + (text.isEmpty() ? "" : ": " + clip(text));
        }
        if (!detail.isEmpty()) {
            return "HTTP " + status + " but the response refused it: " + detail;
        }
        return null;
    }

    /** {@code errors} joined, or a {@code status} that is not {@code ok}; empty when the body says nothing. */
    static String errorDetail(String body) {
        if (body == null || body.isEmpty() || !body.startsWith("{")) {
            return "";
        }
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(body);
        } catch (RuntimeException e) {
            return "";
        }
        Object errors = doc.get("errors");
        List<String> messages = new ArrayList<>();
        collectErrors(errors, messages);
        if (!messages.isEmpty()) {
            return String.join("; ", messages);
        }
        Object status = doc.get("status");
        if (status != null) {
            String value = String.valueOf(status);
            if (!value.isEmpty() && !"ok".equalsIgnoreCase(value)) {
                return "status " + value;
            }
        }
        return "";
    }

    private static void collectErrors(Object node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node instanceof List<?> list) {
            for (Object element : list) {
                collectErrors(element, out);
            }
        } else if (node instanceof Map<?, ?> map) {
            // v2's JSON:API errors are objects; take the human fields if there are any
            Object message = map.get("message");
            if (message != null) {
                out.add(String.valueOf(message));
            } else {
                out.add(String.valueOf(map));
            }
        } else {
            String value = String.valueOf(node).strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    private AtomResult refused(
            int status, String refusal, String what, String endpoint, boolean failOnError, AtomContext context) {
        ErrorType type = refusalType(status);
        String message = "Datadog refused the " + what + " (" + endpoint + "): " + refusal;
        if (!failOnError) {
            context.log("[datadog] WARNING " + message + " (failOnError=false, not failing the build)");
            return AtomResult.success(message)
                    .data("httpStatus", String.valueOf(status))
                    .data(("event".equals(what) ? "eventsAccepted" : "metricsAccepted"), "false");
        }
        int code = status >= 200 && status < 300 ? 40004 : 40003;
        return AtomResult.error(type, code, message);
    }

    /** A bad key or a bad payload is the caller's problem; a rate limit or a 5xx is not. */
    static ErrorType refusalType(int status) {
        if (status == 429 || status >= 500) {
            return ErrorType.THIRD_PART;
        }
        if (status >= 400 && status < 500) {
            return status == 408 ? ErrorType.ENVIRONMENT : ErrorType.USER;
        }
        return ErrorType.THIRD_PART;
    }

    // ------------------------------------------------------------------ the call

    /** One request and the answer to it; {@code skipped} is a request that was not made (dry run, nothing to say). */
    private static final class Attempt {
        final String endpoint;
        final int status;
        final String body;
        final long durationMs;
        final boolean skipped;

        private Attempt(String endpoint, int status, String body, long durationMs, boolean skipped) {
            this.endpoint = endpoint;
            this.status = status;
            this.body = body;
            this.durationMs = durationMs;
            this.skipped = skipped;
        }

        static Attempt skipped() {
            return new Attempt("", 0, "", 0, true);
        }

        String statusText() {
            return skipped ? "" : String.valueOf(status);
        }

        /** {@code true} once we know Datadog did not refuse it; empty when the request was never made. */
        String verdict(boolean nothingToSend) {
            if (skipped) {
                return nothingToSend ? "" : "skipped";
            }
            return "true";
        }

        String describe() {
            return skipped ? "not sent" : "HTTP " + status + " in " + durationMs + " ms";
        }
    }

    private Attempt post(HttpClient client, String endpoint, String apiKey, String body, int timeout, AtomContext context) {
        long started = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    // the plugin authenticates with this header, not with ?api_key= — the query-string form is legacy
                    .header("DD-API-KEY", apiKey)
                    .header("User-Agent", "jenkins-atom-datadog-metrics/1.0.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            // a connection that never happened is not a verdict — reported as an error, never as a rejection
            throw new TransportFailure(endpoint, reason, elapsed);
        }
        long elapsed = System.currentTimeMillis() - started;
        String text = response.body() == null ? "" : response.body();
        context.log("[datadog] POST " + endpoint + " -> " + response.statusCode() + " ("
                + elapsed + " ms, " + text.length() + " bytes)" + (text.isEmpty() ? "" : " " + clip(text)));
        return new Attempt(endpoint, response.statusCode(), text, elapsed, false);
    }

    /** A refused socket is an environment error, so it leaves {@code post} as a throw of its own. */
    static final class TransportFailure extends RuntimeException {
        final String endpoint;
        final long durationMs;

        TransportFailure(String endpoint, String reason, long durationMs) {
            super(reason);
            this.endpoint = endpoint;
            this.durationMs = durationMs;
        }
    }

    // ------------------------------------------------------------------ facts

    /** What the atom knows about the build: {@code x-jenkins.context: ["build"]} first, inputs/env as the fallback. */
    static final class Facts {
        String job = "";
        String number = "";
        String result = "";
        String url = "";
        String host = "";
        String branch = "";
        String jenkinsUrl = "";
        String node = "";
        String userId = "";
        String causes = "";
        long durationMs;
        long pauseMs = -1;
        long waitingMs = -1;
        long timestampSeconds;

        static Facts of(AtomContext context) {
            Facts facts = new Facts();
            BuildInfo build = context.build();
            if (build != null) {
                facts.job = build.getJob();
                facts.number = build.getNumber() == 0 ? "" : String.valueOf(build.getNumber());
                facts.result = build.getResult();
                facts.url = build.getUrl();
                facts.durationMs = build.getDurationMs();
                if (build.getTimestamp() > 0) {
                    facts.timestampSeconds = build.getTimestamp() / 1000L + Math.max(0, build.getDurationMs()) / 1000L;
                }
                facts.causes = String.join("; ", build.getCauses());
            }
            facts.job = first(param(context, "jobName", ""), facts.job, env("JOB_NAME"), "unknown-job");
            facts.number = first(param(context, "buildNumber", ""), facts.number, env("BUILD_NUMBER"), "0");
            facts.result = first(param(context, "buildResult", ""), facts.result, env("BUILD_STATUS"),
                    env("BUILD_RESULT"), "UNKNOWN").toUpperCase(Locale.ROOT);
            facts.url = first(param(context, "buildUrl", ""), facts.url, env("BUILD_URL"), "");
            facts.branch = first(param(context, "branchName", ""), env("BRANCH_NAME"), env("GIT_BRANCH"), "");
            // kept verbatim: a tag is data, and the plugin reports the URL exactly as configured
            facts.jenkinsUrl = first(param(context, "jenkinsUrl", ""), env("JENKINS_URL"), "");
            facts.node = first(param(context, "agentName", ""), env("NODE_NAME"), "");
            facts.userId = first(param(context, "userId", ""), env("BUILD_USER_ID"), "");
            // host is the event/metric host: the agent that ran the build, else this machine
            facts.host = first(param(context, "host", ""), facts.node, "jenkins");

            long duration = number(param(context, "buildDurationMs", ""), -1);
            if (duration < 0) {
                duration = facts.durationMs;
            }
            if (duration <= 0) {
                long started = number(param(context, "buildStartedMs", ""), -1);
                if (started > 0) {
                    duration = System.currentTimeMillis() - started;
                }
            }
            if (duration < 0) {
                duration = 0;
            }
            facts.durationMs = duration;
            facts.pauseMs = number(param(context, "pauseDurationMs", ""), -1);
            facts.waitingMs = number(param(context, "queueDurationMs", ""), -1);

            if (facts.timestampSeconds <= 0) {
                long explicit = number(param(context, "timestampMs", ""), -1);
                facts.timestampSeconds = explicit > 0 ? explicit / 1000L : System.currentTimeMillis() / 1000L;
            }
            return facts;
        }

        String formattedDuration() {
            if (durationMs <= 0) {
                return "";
            }
            double seconds = durationMs / 1000.0;
            return seconds < 60
                    ? String.format(Locale.ROOT, "(%.2f sec)", seconds)
                    : String.format(Locale.ROOT, "(%.2f min)", seconds / 60.0);
        }
    }

    // ------------------------------------------------------------------ tags and series

    static List<String> tags(Facts facts, String custom) {
        List<String> tags = new ArrayList<>();
        tags.add("job:" + facts.job);
        tags.add("build_number:" + facts.number);
        if (!facts.result.isEmpty()) {
            tags.add("result:" + facts.result);
        }
        if (!facts.branch.isEmpty()) {
            tags.add("branch:" + facts.branch);
        }
        if (!facts.node.isEmpty()) {
            tags.add("node:" + facts.node);
        }
        if (!facts.userId.isEmpty()) {
            tags.add("user_id:" + facts.userId);
        }
        if (!facts.jenkinsUrl.isEmpty()) {
            tags.add("jenkins_url:" + facts.jenkinsUrl);
        }
        // the plugin stamps every event with this so its events can be told apart from other sources
        tags.add("event_type:default");
        tags.addAll(parseTags(custom));
        return tags;
    }

    /** {@code key:value} per line ({@code key=value} also accepted); Datadog tags are always {@code key:value}. */
    static List<String> parseTags(String text) {
        List<String> tags = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tags;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            int equals = trimmed.indexOf('=');
            int split = colon > 0 ? colon : equals;
            if (split <= 0 || split == trimmed.length() - 1) {
                throw new IllegalArgumentException("tag must be key:value, got: " + trimmed);
            }
            tags.add(trimmed.substring(0, split).strip() + ":" + trimmed.substring(split + 1).strip());
        }
        return tags;
    }

    /** {@code name=value[:type]} per line; no prefix is applied — a custom metric name is the caller's. */
    static List<String> parseMetrics(String text) {
        List<String> metrics = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return metrics;
        }
        int line = 0;
        for (String raw : text.split("\\R")) {
            line++;
            String trimmed = raw.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("customMetrics line " + line + " must be name=value[:type]: " + trimmed);
            }
            String name = trimmed.substring(0, equals).strip();
            String rest = trimmed.substring(equals + 1).strip();
            String type = "gauge";
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                String candidate = rest.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
                if (V2_TYPES.containsKey(candidate)) {
                    type = candidate;
                    rest = rest.substring(0, colon).strip();
                }
            }
            if (!rest.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
                throw new IllegalArgumentException("customMetrics line " + line + " has no number: " + trimmed);
            }
            metrics.add(name + "=" + rest + ":" + type);
        }
        return metrics;
    }

    private static List<Series> series(
            Facts facts, String prefix, List<String> custom, long timestamp, boolean sendMetrics) {
        List<Series> series = new ArrayList<>();
        if (!sendMetrics) {
            return series;
        }
        String base = prefix == null || prefix.isBlank() ? "" : prefix.strip() + ".";
        if (facts.durationMs > 0) {
            // jenkins.job.duration (gauge, seconds) — DatadogBuildListener: toSeconds(duration)
            series.add(new Series(base + "duration", facts.durationMs / 1000.0, "gauge", timestamp));
            if (facts.pauseMs >= 0) {
                // jenkins.job.build_duration — duration minus the time spent paused; only claimed when pause is known
                double withoutPause = Math.max(0, facts.durationMs - facts.pauseMs) / 1000.0;
                series.add(new Series(base + "build_duration", withoutPause, "gauge", timestamp));
            }
        }
        if (facts.waitingMs >= 0) {
            // jenkins.job.waiting (gauge, seconds)
            series.add(new Series(base + "waiting", facts.waitingMs / 1000.0, "gauge", timestamp));
        }
        // jenkins.job.completed is a counter in the plugin (incrementCounter), i.e. one count per build
        series.add(new Series(base + "completed", 1, "count", timestamp));

        for (String metric : custom) {
            int equals = metric.indexOf('=');
            int colon = metric.lastIndexOf(':');
            String name = metric.substring(0, equals);
            double value = Double.parseDouble(metric.substring(equals + 1, colon));
            String type = metric.substring(colon + 1);
            series.add(new Series(name, value, type, timestamp));
        }
        return series;
    }

    // ------------------------------------------------------------------ small helpers

    record Series(String metric, double value, String type, long timestamp) {}

    /** The API root for a site alias, or {@code apiUrl} when the caller overrode it. */
    static String apiBase(String site, String apiUrl) {
        String override = apiUrl == null ? "" : apiUrl.strip();
        if (!override.isEmpty()) {
            if (!override.startsWith("http://") && !override.startsWith("https://")) {
                throw new IllegalArgumentException("apiUrl must start with http:// or https://, got: " + override);
            }
            return trimTrailingSlash(override);
        }
        String key = site == null ? "" : site.strip().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            key = "datadoghq.com";
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            throw new IllegalArgumentException("site is a host name, not a URL — put the URL in apiUrl: " + site);
        }
        if (key.startsWith("api.")) {
            key = key.substring(4);
        }
        String host = SITES.get(key);
        if (host == null) {
            throw new IllegalArgumentException("unknown Datadog site '" + site + "'; use one of " + SITES.keySet()
                    + " or set apiUrl");
        }
        return "https://" + host + "/api";
    }

    /** Reads an input and expands {@code ${NAME}} from the run's inputs/environment, like the other atoms do. */
    static String param(AtomContext context, String name, String fallback) {
        String raw = context.getParam(name, fallback);
        if (raw == null) {
            return "";
        }
        Matcher matcher = VAR.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = context.getParam(key);
            if (value == null) {
                value = System.getenv(key);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.strip();
    }

    /** The first candidate that is a real value: blank and still-unexpanded {@code ${...}} defaults are skipped. */
    private static String first(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !candidate.contains("${")) {
                return candidate.strip();
            }
        }
        return "";
    }

    private static long number(String text, long fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.strip();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String clip(String text) {
        String one = text.replace('\n', ' ').strip();
        return one.length() > 400 ? one.substring(0, 400) + "…" : one;
    }
}
