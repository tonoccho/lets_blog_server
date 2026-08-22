# Performance Load Tests (k6)

This directory contains load testing scripts using [k6](https://k6.io/), a modern load testing tool written in Go.

## Prerequisites

### Install k6

**macOS:**
```bash
brew install k6
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 \
  --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install -y k6
```

**Windows:**
```bash
choco install k6
```

**Verify installation:**
```bash
k6 version
```

## Available Tests

### 1. Markdown Rendering Load Test
**File:** `load-test-render-endpoint.js`

Tests the markdown rendering endpoint under progressive load.

```bash
k6 run load-test-render-endpoint.js
```

**Profile:**
- Ramp-up: 5 → 10 users over 2 minutes
- Sustain: 10 users for 2 minutes
- Ramp-down: 1 minute
- Total: ~5 minutes

**Thresholds:**
- p(95) response time: < 500ms
- p(99) response time: < 1000ms
- Error rate: < 10%

### 2. Image Generation Load Test
**File:** `load-test-image-generation.js`

Tests image generation and retrieval endpoints.

```bash
k6 run load-test-image-generation.js
```

**Profile:**
- Ramp-up: 3 → 5 users over 1.5 minutes
- Sustain: 5 users for 1.5 minutes
- Ramp-down: 30 seconds
- Total: ~4 minutes

**Thresholds:**
- p(95) response time: < 1000ms
- p(99) response time: < 2000ms
- Error rate: < 15%

### 3. Database Queries Load Test
**File:** `load-test-database-queries.js`

Tests database-heavy read operations.

```bash
k6 run load-test-database-queries.js
```

**Profile:**
- Ramp-up: 10 → 20 users over 2.5 minutes
- Sustain: 20 users for 2 minutes
- Ramp-down: 1 minute
- Total: ~6 minutes

**Thresholds:**
- p(95) response time: < 1000ms
- p(99) response time: < 2000ms
- Error rate: < 10%

### 4. Spike Test
**File:** `load-test-spike.js`

Tests system behavior under sudden traffic spikes.

```bash
k6 run load-test-spike.js
```

**Profile:**
- Normal load: 5 users
- Spike to: 50 users
- Recovery: back to 5 users
- Total: ~2.5 minutes

**Thresholds:**
- p(95) response time: < 2000ms
- p(99) response time: < 5000ms
- Error rate: < 20%

## Quick Start

### 1. Start the API server

```bash
cd api
./gradlew bootRun
```

Wait for the API to start (usually ~10-15 seconds).

### 2. Run a test

```bash
cd api/performance-tests

# Basic run
k6 run load-test-render-endpoint.js

# With HTML report
k6 run --out html=report.html load-test-render-endpoint.js

# With JSON report
k6 run --out json=results.json load-test-render-endpoint.js
```

### 3. View results

```bash
# Open HTML report in browser
open report.html

# Parse JSON results
cat results.json | jq '.metrics.render_duration'
```

## Running All Tests

```bash
#!/bin/bash
set -e

echo "Starting performance test suite..."
echo ""

echo "1. Running render endpoint test..."
k6 run load-test-render-endpoint.js

echo "2. Running database queries test..."
k6 run load-test-database-queries.js

echo "3. Running spike test..."
k6 run load-test-spike.js

echo ""
echo "All tests completed!"
```

Or using the provided script:
```bash
chmod +x run-all-tests.sh
./run-all-tests.sh
```

## Configuration

### Environment Variables

```bash
# Set API base URL (default: http://localhost:8080)
export API_BASE_URL="http://example.com:8080"

# Set project ID for image generation test (default: 1)
export PROJECT_ID="123"

# Run test
k6 run load-test-image-generation.js
```

### Customizing Test Parameters

Edit the `export const options` section in any test file:

```javascript
export const options = {
  stages: [
    { duration: '30s', target: 5 },   // Adjust ramp-up
    { duration: '2m', target: 10 },   // Adjust sustain
    { duration: '1m', target: 0 },    // Adjust ramp-down
  ],
  thresholds: {
    'http_req_duration': ['p(95)<1000'],  // Adjust threshold
  },
};
```

## Output Formats

### Console Output (default)
```bash
k6 run load-test-render-endpoint.js
```

### HTML Report
```bash
k6 run --out html=report.html load-test-render-endpoint.js
# Open report.html in browser
```

### JSON Report
```bash
k6 run --out json=results.json load-test-render-endpoint.js
# Parse with jq
jq '.metrics' results.json
```

### Grafana Cloud
```bash
k6 run --out cloud load-test-render-endpoint.js
# Requires k6 Cloud account
```

## Troubleshooting

### Connection Refused
```
Error: error while making HTTP request
http://localhost:8080/api/...
get http://localhost:8080: dial tcp 127.0.0.1:8080: connection refused
```

**Solution:** Ensure API server is running and accessible.

### High Error Rate
Check if API server is overloaded or has issues:
```bash
# Check API health
curl http://localhost:8080/actuator/health

# Check logs
tail -f api/build/logs/application.log
```

### Memory Issues
If k6 process runs out of memory, reduce concurrent users:
```bash
# Edit the test file and reduce target users in stages
stages: [
  { duration: '30s', target: 2 },  // Reduced from 5
  { duration: '2m', target: 5 },   // Reduced from 10
]
```

## Performance Baselines

See `docs/PERFORMANCE_TESTING.md` for detailed baseline expectations and analysis guidelines.

## Related Documentation

- [k6 Documentation](https://k6.io/docs/)
- [Performance Testing Guide](../docs/PERFORMANCE_TESTING.md)
- [API Documentation](../openapi.json)
