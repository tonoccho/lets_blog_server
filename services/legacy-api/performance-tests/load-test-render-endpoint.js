import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';

const apiBaseUrl = __ENV.API_BASE_URL || 'http://localhost:8080';

// Custom metrics
const renderDuration = new Trend('render_duration');
const renderErrors = new Counter('render_errors');
const renderSuccesses = new Counter('render_successes');
const renderRPS = new Gauge('render_rps');

export const options = {
  stages: [
    { duration: '30s', target: 5 },   // Ramp-up to 5 users
    { duration: '1m30s', target: 10 }, // Ramp-up to 10 users
    { duration: '2m', target: 10 },    // Stay at 10 users
    { duration: '1m', target: 0 },     // Ramp-down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(95)<500', 'p(99)<1000'],
    'http_req_failed': ['rate<0.1'],
    'render_errors': ['count<10'],
  },
};

const testMarkdown = `
# Performance Test Article

## Introduction

This is a test article for performance benchmarking the markdown rendering endpoint.

## Sections

### Section 1
This is **bold** text and this is *italic* text.

### Section 2
- Item 1
- Item 2
- Item 3

### Section 3
\`\`\`javascript
const hello = "world";
console.log(hello);
\`\`\`

## Conclusion

This markdown content is used to test rendering performance.
`;

export default function () {
  const payload = {
    markdown: testMarkdown,
  };

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const res = http.post(
    `${apiBaseUrl}/api/articles/preview`,
    JSON.stringify(payload),
    params
  );

  renderDuration.add(res.timings.duration);

  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
    'has html content': (r) => r.body.includes('<h1>'),
  });

  if (success) {
    renderSuccesses.add(1);
  } else {
    renderErrors.add(1);
  }

  sleep(1);
}
