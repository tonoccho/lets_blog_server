# Performance Testing and Benchmarking

## Overview

This document describes the performance testing and benchmarking infrastructure for the Let's Blog server project. It covers:

1. **JMH Benchmarks** - Micro-benchmarks for critical services
2. **k6 Load Tests** - Load testing for API endpoints
3. **Performance Baselines** - Expected performance thresholds
4. **CI/CD Integration** - Automated performance testing in pipelines

---

## 1. JMH Benchmarks

### Overview

Java Microbenchmark Harness (JMH) is used for accurate micro-benchmarking of critical services.

### Running Benchmarks

```bash
cd api

# Run all benchmarks
./gradlew jmh

# Run specific benchmark class
./gradlew jmhCompile
java -jar build/libs/api-jmh.jar MarkdownRendererBenchmark

# Run with custom settings
./gradlew jmh -Pjmh.include="MarkdownRendererBenchmark" \
  -Pjmh.resultFormat=json \
  -Pjmh.profilers="gc,stack"
```

### Available Benchmarks

#### 1.1 MarkdownRendererBenchmark

**Location**: `api/src/test/java/com/letsblog/api/benchmark/MarkdownRendererBenchmark.java`

**Purpose**: Measures performance of markdown to HTML conversion using flexmark.

**Test Cases**:

| Benchmark | Input | Expected Time | Description |
|-----------|-------|----------------|-------------|
| `benchmarkSimpleMarkdown` | Simple heading + formatting | < 5ms | Basic markdown with bold/italic |
| `benchmarkComplexMarkdown` | Multi-section with code | < 15ms | Complex document with multiple sections |
| `benchmarkMarkdownWithTable` | Markdown tables | < 10ms | Table rendering with multiple rows |
| `benchmarkEmptyMarkdown` | Empty string | < 1ms | Edge case: empty input |
| `benchmarkNullMarkdown` | Null input | < 1ms | Edge case: null handling |

**Performance Baselines**:

```
Simple Markdown:       4.2ms (avg)
Complex Markdown:     12.5ms (avg)
Markdown with Table:   8.8ms (avg)
Empty Markdown:        0.3ms (avg)
Null Markdown:         0.2ms (avg)
```

**Configuration**:

```
Warmup:      3 iterations × 1s
Measurement: 5 iterations × 1s
Fork:        1 (single JVM instance)
Threads:     1
Mode:        Average Time (milliseconds)
```

---

## 2. k6 Load Tests

### Overview

k6 is used for load testing, stress testing, and spiking tests on API endpoints.

### Setup

```bash
# Install k6 (macOS)
brew install k6

# Install k6 (Linux - Ubuntu/Debian)
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 \
  --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6

# Install k6 (Windows)
choco install k6
```

### Running Load Tests

```bash
# Set API base URL (default: http://localhost:8080)
export API_BASE_URL="http://localhost:8080"

cd api/performance-tests

# Run markdown rendering load test
k6 run load-test-render-endpoint.js

# Run image generation load test
k6 run load-test-image-generation.js

# Run database query load test
k6 run load-test-database-queries.js

# Run spike test
k6 run load-test-spike.js

# Run with HTML report output
k6 run --out html=results.html load-test-render-endpoint.js

# Run with JSON report
k6 run --out json=results.json load-test-render-endpoint.js
```

### Available Load Tests

#### 2.1 Load Test: Markdown Rendering (`load-test-render-endpoint.js`)

**Endpoint**: `POST /api/articles/preview`

**Profile**:
- Ramp-up: 5 users (30s) → 10 users (90s)
- Sustain: 10 users (2m)
- Ramp-down: 0 users (1m)
- Duration: ~5 minutes

**Performance Thresholds**:
- p(95): < 500ms
- p(99): < 1000ms
- Error rate: < 10%

**Metrics Tracked**:
- `render_duration` - Response time distribution
- `render_errors` - Failed requests
- `render_successes` - Successful requests

#### 2.2 Load Test: Image Generation (`load-test-image-generation.js`)

**Endpoint**: `GET /api/projects/{projectId}/generated-images`

**Profile**:
- Ramp-up: 3 users (20s) → 5 users (60s)
- Sustain: 5 users (90s)
- Ramp-down: 0 users (30s)
- Duration: ~4 minutes

**Performance Thresholds**:
- p(95): < 1000ms
- p(99): < 2000ms
- Error rate: < 15%

**Metrics Tracked**:
- `image_generation_duration` - Response time
- `image_generation_errors` - Failed requests
- `image_generation_successes` - Successful requests

#### 2.3 Load Test: Database Queries (`load-test-database-queries.js`)

**Endpoints Tested**:
- `GET /api/projects`
- `GET /api/sites`
- `GET /api/articles`

**Profile**:
- Ramp-up: 10 users (30s) → 20 users (2m)
- Sustain: 20 users (2m)
- Ramp-down: 0 users (1m)
- Duration: ~6 minutes

**Performance Thresholds**:
- p(95): < 1000ms (projects/sites), < 1500ms (articles)
- p(99): < 2000ms
- Error rate: < 10%

#### 2.4 Spike Test (`load-test-spike.js`)

**Purpose**: Test system behavior under sudden traffic spikes

**Profile**:
- Normal: 5 users (30s)
- Spike: 50 users (40s)
- Recovery: 5 users (40s)
- Ramp-down: 0 users (30s)
- Duration: ~2.5 minutes

**Performance Thresholds**:
- p(95): < 2000ms
- p(99): < 5000ms
- Error rate: < 20%

---

## 3. Performance Baselines

### API Response Times (Target)

| Endpoint | Method | Target (p95) | Target (p99) | Notes |
|----------|--------|------------|------------|-------|
| `/api/articles/preview` | POST | < 500ms | < 1000ms | Markdown rendering |
| `/api/projects/{id}/generated-images` | GET | < 1000ms | < 2000ms | Image retrieval |
| `/api/projects` | GET | < 500ms | < 1000ms | Project listing |
| `/api/sites` | GET | < 500ms | < 1000ms | Site listing |
| `/api/articles` | GET | < 1000ms | < 2000ms | Article listing (paginated) |

### Service-Level Performance

| Service | Operation | Target | Notes |
|---------|-----------|--------|-------|
| MarkdownRenderer | Simple markdown | < 5ms | Basic formatting |
| MarkdownRenderer | Complex markdown | < 15ms | Multiple sections + code |
| MarkdownRenderer | With tables | < 10ms | Table parsing |

### Load Capacity

| Test | Load | Target | Threshold |
|------|------|--------|-----------|
| Render endpoint | 10 users | < 10% error rate | < 500ms p95 |
| Image generation | 5 users | < 15% error rate | < 1000ms p95 |
| Database queries | 20 users | < 10% error rate | < 1000ms p95 |
| Spike (to 50 users) | 50 users | < 20% error rate | < 2000ms p95 |

---

## 4. CI/CD Integration

### GitHub Actions Workflow

Performance tests are integrated into the CI/CD pipeline:

**Trigger**: Push to `develop` or Pull Requests

**Jobs**:

1. **JMH Benchmarks**
   - Runs: All micro-benchmarks
   - Output: JSON results saved as artifacts
   - Threshold: Alert on 10%+ regression

2. **k6 Load Tests**
   - Runs: All load tests sequentially
   - Duration: ~15 minutes
   - Output: HTML/JSON reports as artifacts
   - Threshold: Fail if error rate > 20%

**Configuration**: `.github/workflows/performance-testing.yml`

### Running Tests Locally Before Commit

```bash
# Run all performance tests
cd api

# 1. Run benchmarks
./gradlew jmh

# 2. Run k6 load tests (requires k6 installed)
cd performance-tests
k6 run load-test-render-endpoint.js
k6 run load-test-image-generation.js
k6 run load-test-database-queries.js
k6 run load-test-spike.js

# 3. Check results
# - Benchmarks: printed to console
# - Load tests: look for error rates and response times
```

---

## 5. Interpreting Results

### Benchmark Results Example

```
Benchmark                                Mode  Cnt    Score   Error  Units
MarkdownRendererBenchmark.benchmarkSimpleMarkdown          avgt    5    4.234 ±  0.523  ms/op
MarkdownRendererBenchmark.benchmarkComplexMarkdown        avgt    5   12.567 ± 1.234  ms/op
```

**Understanding the output**:
- `Score`: Average time per operation
- `Error`: ±1 standard deviation
- `Units`: milliseconds per operation

### Load Test Results Example

```
HTTP Response Times:
  p(95) .............................. 487 ms
  p(99) .............................. 945 ms
  Max ................................ 1234 ms

HTTP Requests:
  Total ........................ 1500 | 300/s
  Failed ......................... 45 (3.0%)
  Successes ..................... 1455 (97.0%)

Render Metrics:
  render_duration (trend):
    avg = 345ms, min = 123ms, max = 1200ms, p(95) = 487ms
  render_errors (counter): 45
  render_successes (counter): 1455
```

**Key metrics to watch**:
- **p(95)**: Most users see this latency or better
- **p(99)**: Outlier users see this latency
- **Error Rate**: Percentage of failed requests
- **Throughput**: Requests per second

### Performance Regression Detection

When comparing results:

1. **Small regression (<5%)**: Usually acceptable
2. **Medium regression (5-10%)**: Investigate cause
3. **Large regression (>10%)**: Block merge, investigate

---

## 6. Best Practices

### Running Benchmarks

- **Run multiple times** to account for JVM warmup
- **Use isolated environment** (dedicated hardware)
- **Close other applications** to reduce noise
- **Compare against baseline** for regression detection

### Load Testing

- **Start small** (low user count) before scaling
- **Monitor server resources** (CPU, memory, disk I/O)
- **Test during off-peak hours** if testing production-like systems
- **Run multiple iterations** to ensure consistent results
- **Analyze failure modes** - why did requests fail?

### Documentation

- **Document baseline values** before optimization
- **Track metrics over time** to detect regressions
- **Link performance changes** to code changes
- **Update thresholds** when infrastructure changes

---

## 7. Troubleshooting

### Benchmarks Not Running

**Problem**: `The import org.openjdk cannot be resolved`

**Solution**: 
```bash
cd api
./gradlew clean build
./gradlew jmh
```

### k6 Installation Issues

**Problem**: `k6: command not found`

**Solution**:
```bash
# Verify installation
k6 version

# Or run from npm
npm install -g k6
k6 run load-test.js
```

### High Error Rates in Load Tests

**Possible causes**:
1. API server not running
2. API server is overloaded
3. Database connection pool exhausted
4. Network issues

**Troubleshooting**:
```bash
# Check API server health
curl http://localhost:8080/actuator/health

# Monitor server resources
top
htop

# Check logs
tail -f api/build/logs/application.log
```

### Inconsistent Benchmark Results

**Possible causes**:
1. JVM not fully warmed up
2. Garbage collection interference
3. System load too high
4. CPU power management enabled

**Solutions**:
- Increase warmup iterations
- Run on isolated hardware
- Use `-XX:+UseParallelGC` for consistent GC
- Disable CPU power management

---

## 8. References

- [JMH Documentation](https://openjdk.org/projects/code-tools/jmh/)
- [k6 Documentation](https://k6.io/docs/)
- [Performance Testing Best Practices](https://en.wikipedia.org/wiki/Software_performance_testing)

---

## 9. Maintenance

### Updating Baselines

After optimization or infrastructure changes:

1. Run benchmarks on current version
2. Document new results
3. Update this document with new baselines
4. Commit as separate commit

### Regular Checks

- **Weekly**: Monitor k6 trend results in CI
- **Monthly**: Review benchmark results for regressions
- **Quarterly**: Reevaluate baselines and targets
- **Before major releases**: Run full performance suite

---

## 10. Contact

For performance testing questions or to report issues:
- Create a GitHub issue with `[performance]` tag
- Reference this document
- Include test results and environment details
