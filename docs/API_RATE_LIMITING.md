# API Rate Limiting and Request Validation Guide

## Overview

The Let's Blog API implements rate limiting and comprehensive input validation to protect against abuse and ensure data quality.

## Rate Limiting

Rate limiting is enforced **at the gateway** (`services/gateway`,
`com.letsblog.gateway.config.RateLimitWebFilter`), which is the single entry point for every
`/api/**` request since issue #560. The limits themselves come from
`services/gateway/src/main/resources/application.yml` (`app.rate-limit.*`, bound by
`RateLimitProperties`) and are implemented with Resilience4j `RateLimiter`s that reject
immediately (`timeoutDuration: 0`) instead of queueing.

### Partition granularity (issue #749)

Since issue #584, Web's server-side (BFF) calls — Server Components, Server Actions and Route
Handlers — also go through the gateway, so a single process-wide `api-global` bucket was shared by
browser traffic and by every backend call made while rendering an admin screen. Measured on the
real gateway access log (24h, 2026-08-29), `api-global` demand peaked at **239 req/min** against a
100 req/min ceiling and produced **399 `429` responses**, i.e. the incident of issue #464 was
recurring.

`api-global` is therefore split **per client**, and internal (BFF) traffic gets its own bucket:

| Request | Detected by | Bucket | Partition key |
| --- | --- | --- | --- |
| External (browser, VSCode extension, …) — always arrives via nginx | `X-Forwarded-For` present | `api-global` | **last** entry of `X-Forwarded-For` (`ip:<addr>`) |
| Internal (Web BFF) with an access token | no `X-Forwarded-For`, `Authorization: Bearer` | `api-internal` | JWT `sub` (`user:<sub>`) |
| Internal without a token (e.g. `proxy.ts` calling `/api/auth/setup-status`) | no `X-Forwarded-For`, no token | `api-internal` | peer address (`peer:<addr>`) |

Rationale for these choices:

- **Last** `X-Forwarded-For` entry, not the first: nginx appends the address it actually observed
  (`$proxy_add_x_forwarded_for`), so a client-supplied `X-Forwarded-For` can only prepend values.
  With exactly one trusted proxy in front of the gateway, the last entry cannot be spoofed.
- **JWT `sub` only for internal requests**: the filter runs before Spring Security's chain
  (`Ordered.HIGHEST_PRECEDENCE + 1`), so the token is parsed *without* signature verification;
  invalid tokens are still rejected downstream with 401. Keying external traffic by `sub` would let
  an attacker mint unlimited random `sub`s and get unlimited buckets, so external traffic is always
  keyed by IP.
- **`auth-endpoint` / `upload-endpoint` / `operation-log-endpoint` stay process-wide.** The first
  two are deliberately *total* ceilings (brute-force resistance, and protection against GPU/disk
  exhaustion by image generation and uploads — the upload limit is also the value that issue #444
  exposes in the admin UI). `operation-log-endpoint` only ever receives internal writes and its
  300 req/min ceiling already has ample headroom.
- Per-client `RateLimiter` instances are kept in memory, capped at 10,000 keys; clients beyond the
  cap share a single fallback bucket so the map cannot grow without bound.

Note for the "run `npm run dev` on the host" setup (see `docs/setup.md`): Web then reaches the
gateway through nginx, so its BFF calls carry `X-Forwarded-For` and are counted as **external**
traffic for the host's IP. Raise `API_RATE_LIMIT_REQUESTS` if that becomes a limitation.

### Configuration

#### 1. External API Rate Limiter (`api-global`)
- **Default Limit**: 100 requests per 1 minute, **per external client IP**
- **Environment Variable**: `API_RATE_LIMIT_REQUESTS` (default: 100), `API_RATE_LIMIT_PERIOD`
- **Applies to**: All API endpoints except auth, upload and operation logs
- **Value rationale**: browser-originated traffic (dashboard SSE and its 30s polling fallback)
  peaked at 54 req/min for a single source IP in the measurement above, so the pre-#749 value is
  kept — only its unit changed from "whole process" to "per client".

#### 1b. Internal (BFF) API Rate Limiter (`api-internal`)
- **Default Limit**: 600 requests per 1 minute, **per logged-in user** (JWT `sub`)
- **Environment Variable**: `INTERNAL_API_RATE_LIMIT_REQUESTS` (default: 600),
  `INTERNAL_API_RATE_LIMIT_PERIOD`
- **Applies to**: the same endpoints as `api-global`, when the request comes from inside `lbs-net`
  (i.e. the `web` container's BFF calls)
- **Value rationale**: the heaviest admin screen (project detail) issues ~12 backend calls per
  render and a back-to-back tour of dashboard / project detail / post list / site management costs
  ~27 calls; measured peak demand was 239 req/min. 600 gives ~2.5x headroom over the measured peak
  while still capping a runaway client loop.

#### 2. Authentication Rate Limiter (`auth-endpoint`)
- **Default Limit**: 5 requests per 1 minute, **process-wide** (not partitioned)
- **Environment Variable**: `AUTH_RATE_LIMIT_REQUESTS` (default: 5)
- **Applies to**: `/auth/*`, `/login`, `/register` endpoints
- **Does not apply to**: the read-only status checks `/api/auth/setup-status` and
  `/api/auth/totp/status`, which use `api-global` / `api-internal` instead
- **Purpose**: Prevents brute force attacks

#### 3. Upload Rate Limiter (`upload-endpoint`)
- **Default Limit**: 10 requests per 1 hour, **process-wide** (not partitioned)
- **Environment Variable**: `UPLOAD_RATE_LIMIT_REQUESTS` (default: 10)
- **Applies to** (allowlist, issue #999): only the actual heavy upload/generation calls —
  `POST /api/media/upload`, `POST /api/ai/image` (exact match, so it doesn't catch
  `/api/ai/image-options`), `POST /api/projects/{id}/asset-images/{generatedImageId}/upload`,
  and `POST /api/projects/{id}/bulk-management/upload` (a real multipart file upload, not an
  image, but resource-intensive in the same way)
- **Does not apply to**: any other endpoint, including every image-related metadata/settings
  endpoint under `/api/projects/{id}/**` (e.g. `image-settings`,
  `image-content-filter-settings`, `article-image-resize-default`,
  `ai-models/image/provider[/selection]`) and `/api/generated-images/**` — these use
  `api-global` instead
- **Why an allowlist and not a blocklist**: before #999, this was a blocklist (`/upload` or
  `/image` substring match, with a short exception list for known-lightweight paths). Every new
  lightweight image-related endpoint had to be remembered and added to the exception list, and
  when it wasn't (e.g. `GET /api/projects/{id}/image-settings`, added in #913), it silently
  shared the 10-req/hour quota with real uploads — opening the project detail page alone could
  exhaust it. The allowlist inverts this: a new lightweight endpoint is safe by default, and
  only genuinely heavy operations need to be added here
- **Purpose**: Prevents resource exhaustion
- **Partitioning**: none — process-wide, deliberately (see "Partition granularity" above)
- **Admin-configurable request count** (*not in effect at the gateway*): before #560, while rate
  limiting lived in the pre-split service, the request-count limit (but not the period) could be
  overridden from the admin
  Web UI at `/admin/system-settings` (`upload_rate_limit_requests`), stored in the
  `system_settings` table, with `-1` disabling the limiter. The gateway has no database, so since
  issue #560 only the static defaults above apply; re-introducing a dynamic override is issue
  #444's scope.

#### 4. Operation Log Rate Limiter (`operation-log-endpoint`)
- **Default Limit**: 300 requests per 1 minute, **process-wide** (not partitioned)
- **Environment Variable**: `OPERATION_LOG_RATE_LIMIT_REQUESTS` (default: 300),
  `OPERATION_LOG_RATE_LIMIT_PERIOD`
- **Applies to**: `/api/operation-logs*`
- **Purpose**: the Web BFF records one operation-log entry per backend call (issue #143), so this
  traffic is roughly 1:1 with `api-internal` traffic. It was split out of `api-global` by issue
  #464 so that logging cannot starve the functional endpoints.

### Response Codes

- **200 OK**: Request processed successfully
- **400 Bad Request**: Request validation failed
  - Response includes field-level validation errors
  - Example: `{"error": "Validation failed", "details": [{"field": "email", "message": "must be a valid email"}]}`
- **429 Too Many Requests**: Rate limit exceeded
  - Response includes `Retry-After` header with seconds to wait
  - Example: `{"error": "Rate limit exceeded", "retry-after": "60"}`

### Retry Logic

When receiving a 429 response:

```bash
# Wait for the time specified in Retry-After header
sleep 60

# Retry the request
curl https://api.example.com/endpoint
```

## Request Validation

### Validation Strategy

All API requests are validated at two levels:

1. **Method-level Validation**: Using Jakarta Bean Validation (`@Valid`, `@NotNull`, `@Email`, etc.)
2. **Business Logic Validation**: Custom validation in service layer

### Common Validation Annotations

```java
@NotNull           // Field must not be null
@NotBlank          // String must not be null or empty
@NotEmpty          // Collection must not be empty
@Size(min=1, max=10) // String/collection size constraints
@Email             // Valid email format
@Pattern(regexp="") // Regex pattern matching
@Min(0) @Max(100)   // Numeric range
```

### Example Request DTO

```java
@Data
public class CreateSiteRequest {
    @NotBlank(message = "Site name is required")
    @Size(min = 1, max = 255, message = "Site name must be between 1 and 255 characters")
    private String siteName;

    @NotBlank(message = "URL is required")
    @Pattern(regexp = "^https?://.*", message = "URL must start with http:// or https://")
    private String siteUrl;

    @NotNull(message = "CMS type is required")
    private CmsType cmsType;
}
```

### Example Controller Endpoint

```java
@PostMapping("/sites")
public ResponseEntity<SiteResponse> createSite(@Valid @RequestBody CreateSiteRequest request) {
    // Business logic here
    return ResponseEntity.status(201).body(response);
}
```

### Validation Error Response

```json
{
  "error": "Validation failed",
  "details": [
    {
      "field": "siteName",
      "message": "Site name is required"
    },
    {
      "field": "cmsType",
      "message": "CMS type is required"
    }
  ]
}
```

## Configuration Examples

### Change which bucket an endpoint uses

Bucket classification lives in one place:
`RateLimitWebFilter#getRateLimiterName(String requestPath)`. Add the path there (and a test case in
`RateLimitWebFilterTest`) rather than introducing per-endpoint configuration. Paths that are not
`/api/**` (e.g. `/actuator/health`) never reach the filter's classification in a meaningful way
because only the gateway's `/api/**` routes are exposed through nginx.

### Adjust Rate Limits for Production

Environment variables:

```bash
# Tighter limits for production (set on the gateway container)
export API_RATE_LIMIT_REQUESTS=100
export API_RATE_LIMIT_PERIOD=60s
export INTERNAL_API_RATE_LIMIT_REQUESTS=600
export INTERNAL_API_RATE_LIMIT_PERIOD=60s
export AUTH_RATE_LIMIT_REQUESTS=5
export AUTH_RATE_LIMIT_PERIOD=60s
export OPERATION_LOG_RATE_LIMIT_REQUESTS=300
export OPERATION_LOG_RATE_LIMIT_PERIOD=60s
export UPLOAD_RATE_LIMIT_REQUESTS=10
export UPLOAD_RATE_LIMIT_PERIOD=3600s
```

### Docker Compose Configuration

```yaml
gateway:
  environment:
    API_RATE_LIMIT_REQUESTS: 100
    INTERNAL_API_RATE_LIMIT_REQUESTS: 600
    AUTH_RATE_LIMIT_REQUESTS: 5
    OPERATION_LOG_RATE_LIMIT_REQUESTS: 300
    UPLOAD_RATE_LIMIT_REQUESTS: 10
```

## Best Practices

### For API Clients

1. **Implement Retry Logic**: Handle 429 responses with exponential backoff
2. **Cache Responses**: Reduce redundant requests
3. **Batch Requests**: Combine multiple operations when possible
4. **Monitor Rate Limits**: Track your usage against configured limits

### For API Maintainers

1. **Monitor Abuse**: Log rate limit violations
2. **Adjust Limits**: Increase limits for trusted clients if needed
3. **Document Limits**: Include rate limit info in API documentation
4. **Progressive Degradation**: Gracefully handle rate limit rejection

### Client Retry Example

```javascript
async function apiCallWithRetry(url, options = {}, maxRetries = 3) {
    for (let i = 0; i < maxRetries; i++) {
        const response = await fetch(url, options);

        if (response.status === 429) {
            const retryAfter = response.headers.get('Retry-After') || '60';
            console.log(`Rate limited. Retrying after ${retryAfter}s`);
            await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
            continue;
        }

        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
    }

    throw new Error('Max retries exceeded');
}
```

## Monitoring

### Rate Limit Metrics

Monitor these metrics in production:

- `rate_limiter.calls` - Total calls per limiter
- `rate_limiter.calls.failed` - Failed/rejected calls
- `rate_limiter.state` - Current limiter state

### Logging

The gateway does not emit a dedicated WARN line for rejections; rejected requests show up in the
gateway access log written by `CorrelationIdWebFilter` with `status=429`:

```
INFO c.l.g.config.CorrelationIdWebFilter : gateway request: method=GET path=/api/projects status=429 duration_ms=1 correlation_id=...
```

To count them: `docker logs lbs-gateway | grep "status=429"`.

## Troubleshooting

### Issue: Getting 429 errors

**Solution**: 
- Check configured rate limits
- Implement retry logic with exponential backoff
- Request limit increase if legitimate use case

### Issue: Validation errors on valid requests

**Solution**:
- Review request payload against DTO annotations
- Check error details in response for specific field issues
- Ensure request Content-Type is `application/json`

### Issue: Rate limiter not working

**Solution**:
- Verify Resilience4j dependencies are included
- Check WebConfig.java for interceptor registration
- Ensure endpoints match interceptor pattern

## Related Files

- `services/gateway/src/main/resources/application.yml` - Rate limiter configuration
  (`app.rate-limit.*`)
- `services/gateway/src/main/java/com/letsblog/gateway/config/RateLimitWebFilter.java` - Rate limit
  enforcement, bucket classification and client partitioning
- `services/gateway/src/main/java/com/letsblog/gateway/config/RateLimitProperties.java` - Bucket
  defaults
- `services/gateway/src/test/java/com/letsblog/gateway/config/RateLimitWebFilterTest.java` - Bucket
  classification and partitioning tests
- `infra/nginx/conf.d/default.conf` - sets the `X-Forwarded-For` chain the partitioning relies on
