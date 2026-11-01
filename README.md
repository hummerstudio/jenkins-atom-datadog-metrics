# jenkins-atom-datadog-metrics

把一次 Jenkins 构建的指标和一条构建事件发到 Datadog。凭据走 `x-jenkins.secrets`，报文按官方 `datadog`
Jenkins 插件（10.0.0）实际发出的形状构造。

**这条原子多守一条规则**：HTTP 2xx 不等于被接受。Datadog 的 intake 用 `202 {"status":"ok"}` /
`202 {"errors":[]}` 表示收下，用非空的 `errors` 数组说明为什么没收；中间的代理还可能把拒绝包成 200。
所以收到 2xx 后仍然要看报文，`errors` 非空就判失败。

## 构建与自测

```bash
./build.sh          # 用 ../jenkins-atom-sdk 最新 jar 打包 dist/datadog-metrics/datadog-metrics.jar
./tools/smoke.sh    # 起本地假 Datadog，断言报文形状 + 鉴权头；不联网
```

`tools/smoke.sh` 覆盖：v1 series + event 的完整字段、v2/series 的对象点与整数 type、202 带 errors 的拒绝、
403 坏 key、503、连不上、缺 key 不发请求、dry run、坏输入、构建上下文（`atom_build_json`）优先于入参。
假服务端自己也会校验文档规定的形状（header、points 类型、事件必填字段），所以形状错了即使断言忘了也会被判 400。

## 端点与字段的来源（不是凭记忆）

1. **官方插件的字节码**：`curl -fsSL https://updates.jenkins.io/latest/datadog.hpi`（302 → `10.0.0`），
   内层 `WEB-INF/lib/datadog.jar` 用 `javap -c -p` 读：
   - `DatadogIntakeUrls.DEFAULT_API_URL_VALUE = "https://api.datadoghq.com/api/"`，`DatadogSite` 里另有
     `api.us3/us5/ap1.datadoghq.com`、`api.datadoghq.eu`、`api.ddog-gov.com`。
   - `DatadogApiClient.postApi`：请求头 `DD-API-KEY`（值取 `Secret.toString`），`application/json`，POST
     到 `url + "v1/series"` / `"v1/events"`。**不是** `?api_key=` 查询串。
   - `DatadogApiClient$ApiMetrics.addMetric`：series 行是 `metric` / `points`（`[[epoch秒, 值]]`）/ `type`
     （`gauge`/`rate`/`count`）/ `host` / `tags`（`rate` 另带 `interval`），`close()` 提交 `{"series":[...]}`。
   - `DatadogApiClient.event`：`title` / `text` / `host` / `aggregation_key` / `date_happened` / `tags` /
     `source_type_name`（固定 `jenkins`）/ `priority` / `alert_type`。
   - `AbstractDatadogBuildEvent`：`date` = 结束时间 `/1000`，即 **epoch 秒**。
   - `BuildFinishedEventImpl`：标题格式串是 `Job %s build #%s %s on %s`；正文是
     `%%% \n[Job %s build #%s](%s) finished with status %s %s\n%s \n%%%`；SUCCESS→`low`+`success`，
     FAILURE→`normal`+`error`，其它→`normal`+`warning`。
   - `DatadogBuildListener`：`jenkins.job.duration` 是 gauge（`toSeconds(duration)`），
     `jenkins.job.completed` 是 `incrementCounter`，`jenkins.job.waiting` 是 gauge（秒）。
2. **插件文档页** <https://plugins.jenkins.io/datadog/>：指标表（`jenkins.job.duration` 秒、
   `jenkins.job.build_duration` 不含暂停、`jenkins.job.completed`、`jenkins.job.waiting` 秒）与默认标签
   `job` / `build_number` / `result` / `branch` / `node` / `jenkins_url` / `user_id`。
3. **Datadog 官方 OpenAPI**（`DataDog/datadog-api-client-python` 的 `.generator/schemas/v1|v2/openapi.yaml`）：
   - `POST /api/v1/series` → `202 {"status":"ok"}`，points 为 `[[ts, value]]`，`type` 是字符串；
   - `POST /api/v1/events` → `202`，但标注 *deprecated … available only to organizations that have previously
     used it*；
   - `POST /api/v2/series` → `202 {"errors":[]}`，points 为 `{timestamp, value}`，`type` 是整数
     0=unspecified / 1=count / 2=rate / 3=gauge，host 放在 `resources: [{name, type:"host"}]`；
   - `POST /api/v2/events` 需要 `apiKeyAuth + appKeyAuth`（APP key），且落在
     `event-management-intake.<site>` 主机、JSON:API 信封。

由此决定：**指标默认 `v1/series`（与插件一致，只需 API key），`apiVersion=2` 走 `v2/series`；事件固定走
`v1/events`**。v2 事件要 APP key 和另一个主机，超出「一个 API key 的构建步骤」的范围，这条限制是查证后的取舍，
不是遗漏。

## 入参

| 名称 | 说明 |
| --- | --- |
| `site` | `us1` / `us3` / `us5` / `eu` / `ap1` / `ap2` / `uk1` / `gov`，决定 `api.<site>` |
| `apiUrl` | API 根（含 `/api`），覆盖 site；测试或私有代理用 |
| `apiVersion` | `auto`(→1) / `1` / `2`，只影响指标 |
| `metricPrefix` | 内置指标前缀，默认 `jenkins.job`，留空则不带 |
| `tags` | 额外标签，每行 `key:value`（`key=value` 也接受） |
| `customMetrics` | 额外指标，每行 `name=value[:gauge\|count\|rate]`，原样发送 |
| `eventTitle` / `eventText` | 留空按插件格式生成；支持 `${BUILD_NUMBER}` 等 |
| `priority` / `alertType` | `auto` 时按结果推导（SUCCESS→low/success，FAILURE→normal/error，其余 normal/warning） |
| `aggregationKey` | 留空取 job 名 |
| `host` | 指标/事件 host，留空取节点名，再退到 `jenkins` |
| `jenkinsUrl` / `jobName` / `buildNumber` / `buildResult` / `buildUrl` / `branchName` / `agentName` / `userId` | 构建事实的入参兜底，默认取同名环境变量；声明了 `x-jenkins.context: ["build"]`，有构建上下文时以它为准 |
| `buildDurationMs` / `pauseDurationMs` / `queueDurationMs` / `timestampMs` | 耗时/暂停/排队/时间戳；暂停没给就不发 `build_duration`，排队没给就不发 `waiting`（不臆造） |
| `sendMetrics` / `sendEvent` / `dryRun` / `failOnError` / `timeoutSeconds` | 开关与超时 |

凭据：`x-jenkins.secrets: {"apiKey": ""}`，原子的 `context.getSecret("apiKey")` 取值，从不从普通入参读 key；
缺失时报 40001 且一个请求都不发。

## 出参

`usedSite`、`usedApiVersion`、`metricsEndpoint`、`eventEndpoint`、`httpStatus`、`eventHttpStatus`、
`metricsAccepted`、`eventsAccepted`、`seriesCount`、`pointTimestampSeconds`、`tagsUsed`、`metricsPayload`、
`eventPayload`。

出参名与入参无一重名（也不是只差大小写）：例如入参 `sendMetrics` 对应出参 `metricsAccepted`。

## 失败分类

| errorCode | errorType | 场景 |
| --- | --- | --- |
| 40001 | USER(1) | `apiKey` 未绑定 |
| 40002 | USER(1) | 站点/`apiUrl`/标签/自定义指标/数字不合法 |
| 40003 | USER(1) / THIRD_PART(2) | HTTP 非 2xx：4xx（401/403/400/413/422）归用户，429/5xx 归第三方 |
| 40004 | THIRD_PART(2) | **2xx 但报文拒绝**（`errors` 非空或 `status` 不是 `ok`） |
| 40005 | ENVIRONMENT(5) | 连不上/超时：交给环境，不当成 Datadog 的判决 |

`failOnError=false` 时拒绝只记 WARNING，并在出参里留 `metricsAccepted=false`，构建不失败。

## 默认发出的指标

| 指标 | 类型 | 值 |
| --- | --- | --- |
| `jenkins.job.duration` | gauge | 构建耗时（秒） |
| `jenkins.job.build_duration` | gauge | 耗时减暂停（秒），仅当给了 `pauseDurationMs` |
| `jenkins.job.waiting` | gauge | 排队时长（秒），仅当给了 `queueDurationMs` |
| `jenkins.job.completed` | count | 1（插件里是 `incrementCounter`） |

标签：`job`、`build_number`、`result`、`branch`、`node`、`user_id`、`jenkins_url`、`event_type:default` 与自定义标签。

## License

MIT
