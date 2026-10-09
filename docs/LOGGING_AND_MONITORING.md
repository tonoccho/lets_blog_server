# Logging and Monitoring

This document describes what the Let's Blog multi-service architecture (gateway + 9 domain
services, see [DOCKER_COMPOSE_ARCHITECTURE.md](./DOCKER_COMPOSE_ARCHITECTURE.md) and the
[architecture decision records](./adr/), or the tracking issue #551) **actually does** for logging
and metrics. Everything below exists in the repository; if you add something, add it here, and
if you find something here that is not in the code, that is a bug in this document.

What an earlier revision of this file described but the repository never contained is listed in
[Removed descriptions](#removed-descriptions-issue-1470), so its absence is a recorded fact and
not a silent omission.

## Overview

- **Correlation ID**: assigned at the gateway and propagated across every synchronous HTTP hop and
  every RabbitMQ message, so one request can be followed by grepping logs (issue #582, C13).
- **Request duration log**: every service writes one line per request with method, path, status,
  duration and correlation ID, so you can see *which hop* of a slow operation was slow
  (issue #1470).
- **Service-to-service call log**: `SyncServiceClient` writes one line per outgoing call with the
  target, duration and whether a retry happened (issue #1470).
- **Latency percentiles**: p95 / p99 of `http.server.requests` from Actuator (issue #1470).
- **Health**: `/actuator/health` per service, aggregated by the gateway and the dashboard
  (issue #589).

Logs go to the console in plain text (`logging.pattern.console` in each `application.yml`, which
includes `[%X{correlationId}]`) and are collected by docker's `json-file` driver
(`docker-compose.yml`). There is no file appender, no JSON encoder and no log aggregation backend.

## Correlation ID and Distributed Tracing

### How a correlation ID is assigned and propagated

1. **gateway** (`services/gateway/.../CorrelationIdWebFilter`) is the single entry point for all
   client traffic. For every request it reads the `X-Correlation-Id` request header; if the
   client didn't send one, it generates a new UUID. The resolved value is set on both the
   downstream request (forwarded by `ProxyHandler`, which copies all headers) and the response
   sent back to the client. Gateway itself also emits one access-log line per request
   (`gateway request: method=... path=... status=... duration_ms=... correlation_id=...`)
   so the entry point shows up in a trace, not just the backend services.
2. **Every servlet-based service** (`identity`, `project`, `content`, `media`, `ai`, `analytics`,
   `publishing`, `platform`, `log-writer`) registers `com.letsblog.common.web.CorrelationIdFilter`
   (lbs-common) as a `@Bean` in a small `CorrelationIdConfig` class. It reads/generates the
   header, stores it in SLF4J MDC under the key `correlationId`, sets it back on the response, and
   clears it once the request finishes. It runs at `Ordered.HIGHEST_PRECEDENCE`, i.e. before
   Spring Security, so even 401/403 responses are logged with a correlation ID.
   `CorrelationIdFilterRegistrationContractTest` fails if a service forgets to register it.
3. **Synchronous service-to-service calls** made through the shared
   `com.letsblog.common.client.SyncServiceClient` (issue #581, C12) automatically forward the
   calling thread's MDC correlation ID as an `X-Correlation-Id` header.
4. **Asynchronous processing via RabbitMQ** (`letsblog.events` / `letsblog.logs`) carries the
   correlation ID as a message header, not a payload field:
   - Publishers attach `com.letsblog.common.messaging.CorrelationIdMessagePostProcessor` to
     their `RabbitTemplate`; it copies the publishing thread's MDC correlation ID onto the
     outgoing message header.
   - Consumers attach `com.letsblog.common.messaging.CorrelationIdListenerAdvice` to their
     listener container factory; it puts the header into MDC for the duration of the
     `@RabbitListener` method and removes it afterward.
   - If a message has no correlation header, the consumer simply runs without setting MDC.

### 処理 ID (processing ID)

「処理 ID」は、1 回の処理(ブラウザの 1 操作、または 1 つの API リクエスト)を全ログ行で束ねる ID で、
上で説明している既存の相関 ID にそのまま付けた呼び名である(改名はしない)。

| 項目 | 内容 |
| --- | --- |
| 名前 | 処理 ID(= 相関 ID) |
| HTTP ヘッダ | `X-Correlation-Id` |
| MDC キー | `correlationId`(ログ行では `correlation_id=`) |
| 形式 | `^[A-Za-z0-9-]{1,64}$`(英数字とハイフンのみ、1〜64 文字) |
| 採番者 | Web 経由では Web の操作 ID。それ以外・形式不一致では gateway(UUID) |

- **形式の検証**: gateway の `CorrelationIdWebFilter` と lbs-common の `CorrelationIdFilter` は、
  受け取った `X-Correlation-Id` が上の形式に合わなければ(改行を含む、65 文字以上、許可外の文字)
  その値を捨てて新しい UUID を採番する。捨てた値はログ偽造の元になりうるため、どのログにも出さない。
- **Web の操作 ID との関係**: `apps/web/src/proxy.ts` が 1 回の画面操作ごとに `x-operation-id` を採番し
  (`operation_logs.operation_id` と同じ値)、`apiRequest`(`apps/web/src/lib/apiClient.ts`)が
  gateway へのすべての呼び出しでその値を `X-Correlation-Id` として送る(未認証の呼び出しでも、
  操作 ID があれば送る)。このため操作 ID から gateway・下流サービスの `service request:` 行まで、
  同じ ID で grep できる。

### Tracing a request end-to-end

Pick a correlation ID (from a response header, or from any log line) and grep every service's
logs for it:

```bash
docker compose logs --no-color | grep '<the-correlation-id>'
```

Every hop carries the same ID: the gateway access log, each service's request duration log
(next section), each `SyncServiceClient` call log, and RabbitMQ-triggered log lines. Sorted by
timestamp, the output is the full path of one request, including the asynchronous parts.

## Request duration log (issue #1470)

`com.letsblog.common.web.RequestDurationLoggingFilter` (lbs-common) writes one line per request
in each of the 9 servlet-based services. It is registered as a `@Bean` in each service's
`config/RequestDurationLoggingConfig`, mirroring `CorrelationIdConfig`.
`RequestDurationFilterRegistrationContractTest` fails if a service forgets to register it.

```
service request: method=GET path=/api/posts status=200 duration_ms=42 correlation_id=3f9c...
service request: method=POST path=/api/media status=200 duration_ms=1873 correlation_id=3f9c... slow_threshold_ms=1000
```

- **Slow requests**: a request whose duration is **greater than** the threshold is logged at
  `WARN` (and carries `slow_threshold_ms`); everything else is `INFO`. Filter on `WARN` +
  `service request:` to list slow requests.
- **Threshold**: `app.request-logging.slow-threshold-ms`, default **1000 ms** (env var
  `APP_REQUEST_LOGGING_SLOW_THRESHOLD_MS`). Rationale for the default: the gateway access log
  showed a median of a few tens of milliseconds with outliers above one second (#1470), so
  1000 ms keeps normal traffic at `INFO` and flags only what a user would notice. Routes that
  are slow by design (LLM generation, rendering, up to the 180 s `SyncCallProfile.LLM`) are always
  `WARN`; that is intentional, because those are the calls whose time breakdown is worth seeing.
- **Not logged**: paths starting with `/actuator` and paths ending in `/stream` (SSE) — the same
  rule as the gateway's `CorrelationIdWebFilter#isSkipLogging`, so health-check polling does not
  fill the log and a long-lived stream is not misread as a slow request.
- **Filter order**: `Ordered.HIGHEST_PRECEDENCE + 1` — just *inside* `CorrelationIdFilter` and
  before Spring Security (-100). Outside it, the line would be written after the correlation ID
  filter had already cleared MDC and would carry no correlation ID. The only time not measured is
  the correlation ID generation itself; authentication failures (401/403) are measured.
- **A request that ends in an exception** is logged with `status=500` and the exception is
  rethrown unchanged.

### Why request bodies are not logged

An earlier revision of this document described logging request and response bodies. That is
deliberately **not** done. Request bodies carry credentials and secrets in this system: the
`Authorization` bearer that `SyncServiceClient` callers forward (`forwardedBearer`, see
[SYNC_SERVICE_CALLS.md](./SYNC_SERVICE_CALLS.md)), API keys, and values handled by
`CredentialCipher` before encryption. A body logger would write those into the log files. Only
method, path (no query string), status, duration and correlation ID are logged; headers and
query strings are not.

### Where a slow operation spends its time

1. Find the correlation ID (response header `X-Correlation-Id`, or the gateway line with the large
   `duration_ms`).
2. `grep` it across services. Each `service request:` line is that service's time for its
   hop; the gateway's own line is the sum seen by the client.
3. The difference between a service's `service request:` time and the `sync call:` lines it
   contains (next section) is time spent in the service itself.

## Service-to-service call log (issue #1470)

`SyncServiceClient` writes one line per call (not per retry attempt):

```
sync call: target=ai-service url=http://ai:8080 operation=POST /api/internal/... duration_ms=912 attempts=1 retried=false outcome=success
```

- `target` is the logical service name, `url` the real destination (to spot misrouting, #827),
  `duration_ms` the time as the caller experienced it, **including retry back-off waits**.
- `attempts` / `retried`: how many tries were made (only idempotent GETs are retried; see
  [SYNC_SERVICE_CALLS.md](./SYNC_SERVICE_CALLS.md)). A request rejected by an open circuit
  breaker is `attempts=0`.
- `outcome` is `success` or the simple name of the `SyncService*Exception` that was thrown.
- Level: `INFO` for a successful call with no retry; `WARN` when a retry happened or the call
  failed. The correlation ID comes from the log pattern (`[%X{correlationId}]`).

## Latency percentiles (issue #1470)

Every service, **gateway included**, sets in `application.yml`:

```yaml
management:
  metrics:
    distribution:
      percentiles:
        http.server.requests: 0.95,0.99
```

`/actuator/metrics/http.server.requests` itself still shows only `COUNT` / `TOTAL_TIME` / `MAX`.
The percentiles are a separate meter:

```bash
docker exec lbs-identity curl -s 'http://localhost:8080/actuator/metrics/http.server.requests.percentile?tag=phi:0.95'
docker exec lbs-identity curl -s 'http://localhost:8080/actuator/metrics/http.server.requests.percentile?tag=phi:0.99'
```

Notes and limits:

- `percentiles-histogram` is **not** used: it only publishes histogram buckets for a Prometheus
  scraper, which this repository does not run, and it does not add p95/p99 to Actuator.
- These are client-side (in-process) percentiles over a decaying window. They cannot be
  aggregated across instances or services.
- On the gateway (WebFlux) the `uri` tag is collapsed to `/api/**`, so the per-endpoint breakdown
  comes from the access log, not from this meter.
- `RequestDurationFilterRegistrationContractTest` fails if a service's `application.yml` loses the
  setting.

## Micrometer Tracing: evaluated, deferred

The original scope for this work considered adopting **Micrometer Tracing** (span/trace IDs,
propagation via `micrometer-tracing-bridge-brave` or `-otel`, and an exporter such as Zipkin).
This was evaluated and deliberately deferred for now:

- None of the services depend on `micrometer-tracing`; metrics come from Spring Boot Actuator's
  built-in Micrometer support (plain metrics, not tracing).
- The acceptance criteria for this issue are satisfied by the simpler MDC/header-based
  correlation ID described above (grep-based tracing), without the added operational cost of
  running/maintaining a trace collector and exporter across nine services.
- Full span-based tracing (with parent/child spans per hop, latency breakdowns per span, etc.)
  remains valuable for deeper performance investigation and should be tracked as a separate,
  explicit issue if/when that need arises — it is a materially larger effort (tracer wiring,
  context propagation across WebFlux/servlet/RabbitMQ boundaries, an exporter/backend) than
  correlation-ID propagation.

## Configuration and log levels

Logging is configured per service in `application.yml` (`logging.level.*`,
`logging.pattern.console`). Standard Logback levels apply: `ERROR` needs attention, `WARN` should
be reviewed (this includes slow requests and retried/failed service calls above), `INFO` is the
default, `DEBUG`/`TRACE` are for local investigation.

`management.endpoints.web.exposure.include` is `health,info,metrics` in every service. Secrets-bearing
endpoints (`env`, `configprops`, `heapdump`) and a Prometheus endpoint are not exposed.

## サービスの死活監視(issue #589)

サービスが11個(gateway + 9ドメイン + Keycloak)になった構成では、「どれが落ちているか」が
一目で分からないと運用できない。判定は次の3層に分かれている。

### 1. 各サービスの `/actuator/health`

全サービスが `health` / `info` / `metrics` を公開する。認証ゲート(ADR-0008)の
`PUBLIC_PATHS` に `/actuator/**` が入っているため<b>未認証で到達できる</b>。
`env` / `configprops` / `heapdump` のような秘匿情報を含みうるものは公開していない。

```bash
docker exec lbs-content curl -s http://localhost:8080/actuator/health
```

docker compose の healthcheck もこのエンドポイントを使う(`x-actuator-healthcheck`)。

### 2. gateway の集約ヘルス

gateway は下流9サービスの `/actuator/health` を集約する
(`services/gateway/.../DownstreamHealthConfig`、#560 / #643 / #743)。
どのサービスが不健全かはコンポーネント名で分かる。

```bash
docker exec lbs-gateway curl -s http://localhost:8080/actuator/health | jq '.components | with_entries(select(.key | endswith("Service")))'
```

```json
{
  "identityService": { "status": "UP" },
  "contentService":  { "status": "DOWN" }
}
```

サービスを増やしたときは `DownstreamHealthConfig` に Bean を足す。
`DownstreamHealthConfigContractTest` が `services/` 配下のディレクトリを列挙して
突き合わせるので、足し忘れるとテストが落ちる。

### 3. ダッシュボード(Web)

`/`(ログイン後のトップ)の「接続サービスの状態」に次を表示する。実装は
platform-service の `ConnectedServiceStatusService`。

| 区分 | 対象 |
|---|---|
| 外部依存 | データベース、LLM、ComfyUI、PlantUML、WordPress プロビジョニングエージェント、Penpot、Brave Search |
| **Let's Blog 自身の9サービス** | identity / project / content / media / ai / analytics / publishing / platform / log-writer(#589で追加) |
| **RabbitMQ のキュー滞留・DLQ 滞留** | 全キュー(#589で追加) |

自サービスの状態は **gateway の集約ヘルスを展開**して得る(各サービスを個別に叩かない)。
同じ判定が2箇所に分かれて食い違うのを避けるため。gateway へ到達できない場合は9件すべてを
「判定不能(ERROR)」として返す。判定できないものを「正常」に見せないため。

管理者向けの詳細診断(`GET /api/dashboard/service-status/detail`)には
**「停止時の影響」**の列がある。サービス名だけでは、落ちたときに何が使えなくなるか
運用する人が判断できないため(`LetsBlogServiceStatusService.IMPACT`)。

### RabbitMQ の滞留判定

判定には Management HTTP API(`/api/queues`)を使う。AMQP の接続だけでは各キューの
滞留数を取れない。

| 条件 | 判定 | 理由 |
|---|---|---|
| `*.dlq` に1件でもある | **ERROR** | リトライ上限を超えて処理できなかったイベントが確実に存在する。投稿公開後のキャッシュ無効化やプロジェクト削除の後始末が実行されていない |
| 通常キューが100件以上 | WARNING | コンシューマーが追いつけていない。本システムのイベントは人の操作に紐づくもので、定常的に積み上がる性質ではない |
| Management API へ到達できない | WARNING | 滞留の有無が判定できないだけで、ブローカーの死活は他の経路でも分かる |

DLQ の中身を見るには:

```bash
docker exec lbs-rabbitmq rabbitmqctl list_queues name messages | grep '\.dlq'
```

## Troubleshooting

- **No `service request:` line for a request**: the path starts with `/actuator` or ends in
  `/stream` (skipped on purpose), or the service's `RequestDurationLoggingConfig` is missing
  (the contract test should have caught it).
- **No correlation ID (`correlation_id=-`) on a line**: the request did not pass through
  `CorrelationIdFilter`; check `CorrelationIdConfig` and that the service's
  `logging.pattern.console` contains `%X{correlationId}`.
- **No `http.server.requests.percentile` meter**: the meter appears only after the first request
  has been served; check `management.metrics.distribution.percentiles` in `application.yml`.
- **Metrics endpoint unreachable**: `curl http://localhost:8080/actuator/metrics` inside the
  container; `management.endpoints.web.exposure.include` must contain `metrics`.

## Security Considerations

- Never log credentials, API keys or tokens. This is why request bodies, headers and query
  strings are not logged.
- Restrict access to the docker log store to authorized personnel.
- Do not log personally identifiable information; the correlation ID is the join key, not the
  user's identity.

## References

- [Micrometer Documentation](https://micrometer.io/docs)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)
- [SYNC_SERVICE_CALLS.md](./SYNC_SERVICE_CALLS.md)

## Removed descriptions (issue #1470)

An earlier revision described the items below. **None of them exists in the repository.** They are
removed rather than silently dropped, in the style of
[ACCEPTANCE_CRITERIA.md §4.1](./ACCEPTANCE_CRITERIA.md), so nobody reads their absence as "it was
there and got lost".

| Previously described | Reality | Disposition |
| --- | --- | --- |
| `HttpLoggingFilter` writing `duration_ms` etc. in each service | The class never existed in any `*.java` | Replaced by `RequestDurationLoggingFilter` (above), with a different, narrower log line |
| Request/response body logging, client IP, User-Agent, content type, query parameters | Never implemented | Bodies deliberately **not** adopted ([why](#why-request-bodies-are-not-logged)). Other fields are not logged either |
| JSON logs via `logstash-logback-encoder` and `logback-spring.xml` (prod profile) | No `logback*.xml` and no `logstash` dependency in any `build.gradle` | Out of scope of #1470: nothing reads JSON until a log aggregation backend exists. File a new Issue when that is decided |
| `GET /api/metrics/prometheus`, Prometheus scrape configuration | No `micrometer-registry-prometheus` anywhere; only `health,info,metrics` are exposed | Out of scope of #1470. Percentiles are served from `metrics` instead |
| Application metrics `api.request.count`, `api.request.error`, `api.response.time`, `database.query.count`, `database.query.time` | Not defined anywhere. The real meter is `http.server.requests` | Use `http.server.requests` and its `.percentile` meter |
| `legacy-api` as the service with JSON logging and `HttpLoggingFilter` | `services/legacy-api` does not exist (it was dismantled into the domain services) | References removed |
| `application.log` / `application-json.log` with 100 MB / daily / 30-day / 10 GB rotation, `./logs`, `/var/log/lets-blog-api/` | No file appender; docker's `json-file` driver only (`docker-compose.yml`) | Removed |
| `GET /api/health`, `GET /api/metrics` paths | The endpoints are `/actuator/health` and `/actuator/metrics` | Corrected |
| `application-prod.yml` logging profile, `logging.json.enabled`, `logging.path` | None of these keys exist in any service | Removed |
| Filebeat / Datadog / Splunk forwarding recipes | No such integration exists | Removed |
| Alert rule examples (`api.request.error`, `api.response.time_p95`) | No alerting system exists | Removed |
| Troubleshooting for `logback-spring.xml` and `/var/log/lets-blog-api/` permissions | Nothing to troubleshoot | Replaced by the checks below |
| "JSON logging overhead", "async appenders" performance notes | No JSON logging | Removed |
| Examples of `log.info("...", new Object[]{...})` "structured fields" | Not a structured-logging API; misleading | Removed |
