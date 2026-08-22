import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const apiBaseUrl = __ENV.API_BASE_URL || 'http://localhost:8080';

const spikeDuration = new Trend('spike_duration');
const spikeErrors = new Counter('spike_errors');
const spikeSuccesses = new Counter('spike_successes');

export const options = {
  stages: [
    { duration: '30s', target: 5 },    // Normal load
    { duration: '10s', target: 50 },   // Spike to 50 users
    { duration: '30s', target: 50 },   // Stay at spike
    { duration: '10s', target: 5 },    // Back to normal
    { duration: '30s', target: 0 },    // Ramp-down
  ],
  thresholds: {
    'http_req_duration': ['p(95)<2000', 'p(99)<5000'],
    'http_req_failed': ['rate<0.2'],
    'spike_errors': ['count<20'],
  },
};

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Test main endpoints under spike load
  const endpoints = [
    { name: 'projects', url: '/api/projects?page=0&size=10' },
    { name: 'sites', url: '/api/sites?page=0&size=10' },
    { name: 'articles', url: '/api/articles?page=0&size=5' },
  ];

  for (const endpoint of endpoints) {
    const res = http.get(`${apiBaseUrl}${endpoint.url}`, params);

    spikeDuration.add(res.timings.duration);

    const success = check(res, {
      [`${endpoint.name} status is 200`]: (r) => r.status === 200,
      [`${endpoint.name} response time < 2000ms`]: (r) =>
        r.timings.duration < 2000,
    });

    if (success) {
      spikeSuccesses.add(1);
    } else {
      spikeErrors.add(1);
    }
  }

  sleep(1);
}
