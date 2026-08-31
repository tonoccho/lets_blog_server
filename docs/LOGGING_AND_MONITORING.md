# Logging and Monitoring Setup

This document describes the logging and metrics collection framework across the Let's Blog
multi-service architecture (gateway + domain services, see
[DOCKER_COMPOSE_ARCHITECTURE.md](./DOCKER_COMPOSE_ARCHITECTURE.md) and the
[architecture decision records](./adr/), or the tracking issue #551).

## Overview

The platform uses a logging and monitoring strategy that includes:
- **Correlation ID / distributed tracing**: a single ID assigned at the gateway and propagated
  across every synchronous HTTP hop and every asynchronous RabbitMQ message, so one request's
  full path can be reconstructed by grepping logs across services (issue #582, C13)
- **Structured Logging**: JSON-formatted logs with contextual information (currently rolled out
  to `legacy-api`; see [Structured JSON logging rollout status](#structured-json-logging-rollout-status))
- **Request/Response Logging**: HTTP traffic logging with performance metrics
- **Metrics Collection**: Micrometer-based metrics for monitoring application health
- **Log Levels**: Configurable log levels for different environments

## Correlation ID and Distributed Tracing

### How a correlation ID is assigned and propagated

1. **gateway** (`services/gateway/.../CorrelationIdWebFilter`) is the single entry point for all
   client traffic. For every request it reads the `X-Correlation-Id` request header; if the
   client didn't send one, it generates a new UUID. The resolved value is set on both the
   downstream request (forwarded by `ProxyHandler`, which copies all headers) and the response
   sent back to the client. Gateway itself also emits one access-log line per request
   (`gateway request: method=... path=... status=... duration_ms=... correlation_id=...`)
   so the entry point shows up in a trace, not just the backend services.
2. **Every servlet-based service** (`legacy-api`, `identity`, `content`, `media`, `ai`,
   `analytics`, `log-writer`) registers `com.letsblog.common.web.CorrelationIdFilter`
   (lbs-common) as a `@Bean` in a small `CorrelationIdConfig` class. This filter mirrors the
   gateway's behavior for the servlet stack: it reads/generates the header, stores it in SLF4J
   MDC under the key `correlationId`, sets it back on the response, and clears it once the
   request finishes. It runs at `Ordered.HIGHEST_PRECEDENCE`, i.e. before Spring Security, so
   even 401/403 responses are logged with a correlation ID.
3. **Synchronous service-to-service calls** made through the shared
   `com.letsblog.common.client.SyncServiceClient` (issue #581, C12) automatically forward the
   calling thread's MDC correlation ID as an `X-Correlation-Id` header — callers do not need to
   set this themselves.
4. **Asynchronous processing via RabbitMQ** (`letsblog.events` / `letsblog.logs`) carries the
   correlation ID as a message header, not a payload field (so existing event/message record
   types didn't need to change):
   - Publishers attach `com.letsblog.common.messaging.CorrelationIdMessagePostProcessor` to
     their `RabbitTemplate` via `setBeforePublishPostProcessors(...)`. It copies the publishing
     thread's MDC correlation ID onto the outgoing message header.
   - Consumers attach `com.letsblog.common.messaging.CorrelationIdListenerAdvice` to their
     `SimpleRabbitListenerContainerFactory` via `setAdviceChain(...)`. It reads the header off
     the raw AMQP message before the `@RabbitListener` method runs, puts it into MDC for the
     duration of that method, and removes it afterward — so the listener's logs (and anything
     it calls, e.g. `IdempotentEventHandler`) carry the same ID as the request that originally
     triggered the publish.
   - If a message has no correlation header (e.g. it predates this change, or was published by
     code outside an HTTP/MDC context), the consumer simply runs without setting MDC.

### Tracing a request end-to-end

With the above in place, pick a correlation ID (from a response header, or from any log line)
and grep every service's logs for it — including `docker compose logs` if running locally:

```bash
docker compose logs --no-color | grep '<the-correlation-id>'
```

Because every hop (gateway access log, each service's request log via
`CorrelationIdFilter`/`HttpLoggingFilter`, and any RabbitMQ-triggered log lines) carries the
same ID, the output can be sorted by timestamp to reconstruct the full path of one request,
including the asynchronous parts.

### Micrometer Tracing: evaluated, deferred

The original scope for this work considered adopting **Micrometer Tracing** (span/trace IDs,
propagation via `micrometer-tracing-bridge-brave` or `-otel`, and an exporter such as Zipkin).
This was evaluated and deliberately deferred for now:

- None of the services currently depend on `micrometer-tracing`; only `legacy-api` has
  `spring-boot-starter-micrometer-metrics` (plain metrics, not tracing).
- The acceptance criteria for this issue are satisfied by the simpler MDC/header-based
  correlation ID described above (grep-based tracing), without the added operational cost of
  running/maintaining a trace collector and exporter across nine services.
- Full span-based tracing (with parent/child spans per hop, latency breakdowns per span, etc.)
  remains valuable for deeper performance investigation and should be tracked as a separate,
  explicit issue if/when that need arises — it is a materially larger effort (tracer wiring,
  context propagation across WebFlux/servlet/RabbitMQ boundaries, an exporter/backend) than
  correlation-ID propagation.

### Structured JSON logging rollout status

Only `legacy-api` currently has `logstash-logback-encoder` + a custom `logback-spring.xml`
(JSON output in the `prod` profile, plain text in other profiles — both patterns now include
`[%X{correlationId}]`). The other services (`identity`, `content`, `media`, `ai`, `analytics`,
`log-writer`) don't have a custom Logback configuration; they use Spring Boot's default console
appender with `logging.pattern.console` set in `application.yml` to include
`[%X{correlationId}]`, which is enough for grep-based tracing but is plain text, not JSON.
Rolling `logstash-logback-encoder` out to the other services (for log-aggregation tooling that
expects JSON) is a reasonable follow-up but is a separate, orthogonal piece of work from
correlation ID propagation — consider filing it as its own issue if needed.

`gateway` is a reactive (WebFlux) service; MDC is thread-bound and doesn't propagate reliably
across Reactor operators without additional context-propagation wiring, so its access log line
passes the correlation ID as a plain log argument instead of relying on MDC (see
`CorrelationIdWebFilter` above).

## Logging Architecture

### SLF4J and Logback

The application uses **SLF4J** (Simple Logging Facade for Java) with **Logback** as the logging backend. This provides:
- Structured logging capabilities
- JSON formatting support via Logstash Logback Encoder
- Rolling file appenders with retention policies
- Environment-specific logging configurations

### Log Format

#### Development Environment
In development, logs are printed to console and file in a human-readable format:

```
2026-08-08 14:30:45.123 [main] INFO  com.letsblog.api.LetsBlogApiApplication - Starting Let's Blog API
2026-08-08 14:30:46.456 [http-nio-8080-exec-1] DEBUG com.letsblog.api.controller.PostController - Processing request to /api/posts
```

#### Production Environment
In production, logs are formatted as JSON for easy parsing and log aggregation:

```json
{
  "@timestamp": "2026-08-08T14:30:45.123Z",
  "message": "HTTP request received",
  "level": "INFO",
  "logger": "com.letsblog.api.config.HttpLoggingFilter",
  "thread": "http-nio-8080-exec-1",
  "method": "GET",
  "path": "/api/posts/123",
  "status": 200,
  "duration_ms": 45,
  "application": "lets-blog-api",
  "environment": "production"
}
```

### Configuration

#### application.yml (Development)

```yaml
logging:
  level:
    root: INFO
    com.letsblog.api: DEBUG
    org.springframework.web: DEBUG
  path: ./logs
  json:
    enabled: false

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    enable:
      jvm: true
      process: true
      system: true
      logback: true
```

#### application-prod.yml (Production)

```yaml
logging:
  level:
    root: INFO
    com.letsblog.api: INFO
  path: /var/log/lets-blog-api
  json:
    enabled: true
```

### Log Levels

- **ERROR**: Critical errors that need immediate attention
- **WARN**: Warning conditions that should be reviewed
- **INFO**: General informational messages (default for production)
- **DEBUG**: Detailed debugging information (development only)
- **TRACE**: Very detailed tracing (rarely used)

### Log Rotation

Logs are rotated based on:
- **File Size**: 100 MB maximum per file
- **Time**: Daily rotation
- **Retention**: 30 days of logs kept
- **Total Size Cap**: 10 GB maximum storage

Log files are stored in:
- **Development**: `./logs/`
- **Production**: `/var/log/lets-blog-api/`

### Log Files

1. **application.log** - Standard text format logs
2. **application-json.log** - JSON structured logs (production only)

## HTTP Request/Response Logging

### HttpLoggingFilter

The `HttpLoggingFilter` automatically logs all HTTP requests and responses with the following information:

#### Logged Data

```
- HTTP method (GET, POST, etc.)
- Request path
- Query parameters
- Response status code
- Request/response duration (ms)
- Content type
- Client IP address
- User-Agent header
- Request body (for non-file uploads)
- Response body (for errors or slow requests)
```

#### Thresholds

- **Detailed Logging**: Requests taking > 100ms or responses with status >= 400
- **Error Logging**: Responses with status >= 500
- **Warning Logging**: Responses with status >= 400

#### Excluded Paths

The following paths are not logged to reduce noise:
- `/api/health`
- `/api/metrics`
- `/v3/api-docs`
- `/swagger-ui`

### Example Log Output

```json
{
  "correlation_id": "b3f1c9de-6e3a-4a7e-9c2f-1a2b3c4d5e6f",
  "method": "POST",
  "path": "/api/posts",
  "status": 201,
  "duration_ms": 234,
  "content_type": "application/json",
  "remote_addr": "192.168.1.100",
  "user_agent": "Mozilla/5.0...",
  "request_body": "{\"title\":\"New Post\",\"content\":\"...\"}"
}
```

`correlation_id` (issue #582) is read from MDC (set by `CorrelationIdFilter`, which always runs
before `HttpLoggingFilter`). It's also available as a top-level field in the JSON encoder output
via `includeContext=true` even without this explicit field, but `HttpLoggingFilter` includes it
directly for consistency between the JSON and plain-text log formats.

## Metrics Collection with Micrometer

### Available Metrics

#### JVM Metrics
- Heap memory usage
- Non-heap memory usage
- Garbage collection statistics
- Thread count and states
- Class loading

#### Process Metrics
- CPU usage
- System load average
- File descriptors
- Process memory

#### Application Metrics
- API request count (`api.request.count`)
- API error count (`api.request.error`)
- API response time (`api.response.time`)
- Database query count (`database.query.count`)
- Database query time (`database.query.time`)

### Accessing Metrics

Metrics are exposed at the following endpoints:

#### Health Check
```
GET /api/health
```

Example response:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

#### Metrics Endpoint
```
GET /api/metrics
```

Example response:
```json
{
  "names": [
    "jvm.memory.used",
    "jvm.gc.memory.allocated",
    "api.request.count",
    "api.response.time",
    ...
  ]
}
```

#### Prometheus Metrics
```
GET /api/metrics/prometheus
```

Returns metrics in Prometheus format suitable for scraping.

### Prometheus Integration

The application exposes metrics in Prometheus format. Configure your Prometheus instance to scrape:

```yaml
scrape_configs:
  - job_name: 'lets-blog-api'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/api/metrics/prometheus'
```

## Structured Logging Best Practices

### Using SLF4J Logger

```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExampleService {
    public void processPost(Post post) {
        log.debug("Processing post: {}", post.getId());
        try {
            // process post
            log.info("Post processed successfully: {}", post.getId());
        } catch (Exception e) {
            log.error("Failed to process post: {}", post.getId(), e);
        }
    }
}
```

### Structured Context

When logging contextual information:

```java
// Good: Structured fields
log.info("User login", new Object[] {
    "user_id", userId,
    "ip_address", ipAddress,
    "timestamp", System.currentTimeMillis()
});

// Better: Use a Map for clarity
Map<String, Object> context = new HashMap<>();
context.put("user_id", userId);
context.put("ip_address", ipAddress);
log.info("User login - {}", context);
```

## Log Aggregation

### Forwarding Logs to External Services

To integrate with log aggregation services (ELK, Datadog, Splunk, etc.):

1. **Filebeat** (for ELK Stack)
   - Configure Filebeat to read logs from `/var/log/lets-blog-api/`
   - Parse JSON logs and forward to Elasticsearch

2. **Datadog Agent**
   - Configure the agent to monitor log files
   - JSON logs are automatically parsed

3. **Splunk**
   - Use Splunk Universal Forwarder
   - Configure JSON source type for automatic parsing

### Example Filebeat Configuration

```yaml
filebeat.inputs:
  - type: log
    enabled: true
    paths:
      - /var/log/lets-blog-api/application-json.log
    json.message_key: message
    json.keys_under_root: true
    json.add_error_key: true
    fields:
      service: lets-blog-api

output.elasticsearch:
  hosts: ["localhost:9200"]
  index: "lets-blog-api-%{+yyyy.MM.dd}"
```

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

## Monitoring and Alerting

### Key Metrics to Monitor

1. **Availability**
   - API response status distribution
   - Error rate (errors per minute)

2. **Performance**
   - API response time (p50, p95, p99)
   - Request throughput (requests per second)
   - Database query time

3. **Resource Usage**
   - JVM heap memory usage
   - CPU utilization
   - Disk space usage

4. **Business Metrics**
   - Number of API requests
   - Error types and counts

### Alert Examples

```yaml
# High error rate
alert: HighErrorRate
condition: rate(api.request.error[5m]) > 0.05
duration: 5m
severity: critical

# Slow API response
alert: SlowAPIResponse
condition: api.response.time_p95 > 1000
duration: 10m
severity: warning

# High JVM memory usage
alert: HighMemoryUsage
condition: jvm.memory.used / jvm.memory.max > 0.85
duration: 5m
severity: warning
```

## Troubleshooting

### Logs Not Appearing

1. **Check log level configuration**
   - Verify `logging.level` in application.yml
   - Check individual logger levels

2. **Check log file permissions**
   - Ensure the application has write permissions to `/var/log/lets-blog-api/`
   - Check directory exists: `ls -la /var/log/lets-blog-api/`

3. **Check logback configuration**
   - Verify `logback-spring.xml` is in classpath
   - Review Spring profiles in use

### Metrics Not Available

1. **Verify Actuator is enabled**
   - Check `management.endpoints.web.exposure.include` in application.yml

2. **Check endpoint access**
   - Health: `curl http://localhost:8080/api/health`
   - Metrics: `curl http://localhost:8080/api/metrics`

3. **Verify authentication**
   - Some health details require `when-authorized` setting
   - Ensure you have proper credentials if using authentication

## Performance Considerations

- **JSON Logging Overhead**: JSON formatting adds ~5-10% overhead compared to text logging
- **Async Appenders**: For high-volume logging, consider async appenders in production
- **Metrics Collection**: Micrometer has minimal overhead (~1-2%)
- **Request Logging**: HTTP logging filter processes all requests with minimal impact

## Security Considerations

- **Sensitive Data**: Be careful not to log sensitive information (passwords, API keys, tokens)
- **Log Retention**: Ensure logs are retained according to compliance requirements
- **Log Access**: Restrict access to log files to authorized personnel
- **PII Handling**: Implement log sanitization for personally identifiable information

## References

- [Logback Documentation](https://logback.qos.ch/documentation.html)
- [Logstash Logback Encoder](https://github.com/logstash/logstash-logback-encoder)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)
- [Prometheus Documentation](https://prometheus.io/docs/)
