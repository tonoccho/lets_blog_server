# API Rate Limiting and Request Validation Guide

## Overview

The Let's Blog API implements rate limiting and comprehensive input validation to protect against abuse and ensure data quality.

## Rate Limiting

### Configuration

Rate limiting is configured in `application.yml` using Resilience4j. Three rate limiter profiles are available:

#### 1. Global API Rate Limiter (`api-global`)
- **Default Limit**: 100 requests per 1 minute
- **Environment Variable**: `API_RATE_LIMIT_REQUESTS` (default: 100)
- **Applies to**: All API endpoints except auth and upload

#### 2. Authentication Rate Limiter (`auth-endpoint`)
- **Default Limit**: 5 requests per 1 minute
- **Environment Variable**: `AUTH_RATE_LIMIT_REQUESTS` (default: 5)
- **Applies to**: `/auth/*`, `/login`, `/register` endpoints
- **Purpose**: Prevents brute force attacks

#### 3. Upload Rate Limiter (`upload-endpoint`)
- **Default Limit**: 10 requests per 1 hour
- **Environment Variable**: `UPLOAD_RATE_LIMIT_REQUESTS` (default: 10)
- **Applies to**: `/upload/*`, `/image/*` endpoints (actual file uploads and AI image generation)
- **Does not apply to**: lightweight metadata/settings endpoints under the same paths, e.g.
  `/api/ai/image-options`, `/api/projects/{id}/image-generation-prompt-defaults`,
  `/api/projects/{id}/image-generation-size-defaults` — these use `api-global` instead so
  that opening the asset-generation panel or changing defaults doesn't consume the same
  quota as the actual upload/generation calls (see issue #442)
- **Purpose**: Prevents resource exhaustion

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

### Disable Rate Limiting for Specific Endpoints

Modify `WebConfig.java`:

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                    "/api/health",
                    "/api/metrics",
                    "/api/public/**"  // Add public endpoints here
            );
}
```

### Adjust Rate Limits for Production

Environment variables:

```bash
# Tighter limits for production
export API_RATE_LIMIT_REQUESTS=100
export API_RATE_LIMIT_PERIOD=1m
export AUTH_RATE_LIMIT_REQUESTS=5
export AUTH_RATE_LIMIT_PERIOD=1m
export UPLOAD_RATE_LIMIT_REQUESTS=10
export UPLOAD_RATE_LIMIT_PERIOD=1h
```

### Docker Compose Configuration

```yaml
api:
  environment:
    API_RATE_LIMIT_REQUESTS: 100
    AUTH_RATE_LIMIT_REQUESTS: 5
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

Rate limit violations are logged at WARN level:

```
WARN com.letsblog.api.config.RateLimitInterceptor - Rate limit exceeded for /api/posts (limiter: api-global)
```

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

- `application.yml` - Rate limiter configuration
- `api/src/main/java/com/letsblog/api/config/RateLimitInterceptor.java` - Rate limit enforcement
- `api/src/main/java/com/letsblog/api/config/WebConfig.java` - Web configuration
- `api/src/main/java/com/letsblog/api/config/GlobalExceptionHandler.java` - Error handling
