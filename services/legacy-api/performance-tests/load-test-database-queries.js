import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const apiBaseUrl = __ENV.API_BASE_URL || 'http://localhost:8080';

const queryDuration = new Trend('db_query_duration');
const queryErrors = new Counter('db_query_errors');
const querySuccesses = new Counter('db_query_successes');

export const options = {
  stages: [
    { duration: '30s', target: 10 },  // Ramp-up to 10 users
    { duration: '2m', target: 20 },   // Ramp-up to 20 users
    { duration: '2m', target: 20 },   // Stay at 20 users
    { duration: '1m', target: 0 },    // Ramp-down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed': ['rate<0.1'],
    'db_query_errors': ['count<15'],
  },
};

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Test: Get projects
  const projectsRes = http.get(
    `${apiBaseUrl}/api/projects?page=0&size=20`,
    params
  );

  queryDuration.add(projectsRes.timings.duration);

  const projectSuccess = check(projectsRes, {
    'projects status is 200': (r) => r.status === 200,
    'projects response time < 500ms': (r) => r.timings.duration < 500,
  });

  if (projectSuccess) {
    querySuccesses.add(1);
  } else {
    queryErrors.add(1);
  }

  sleep(0.5);

  // Test: Get sites
  const sitesRes = http.get(
    `${apiBaseUrl}/api/sites?page=0&size=20`,
    params
  );

  queryDuration.add(sitesRes.timings.duration);

  const siteSuccess = check(sitesRes, {
    'sites status is 200': (r) => r.status === 200,
    'sites response time < 500ms': (r) => r.timings.duration < 500,
  });

  if (siteSuccess) {
    querySuccesses.add(1);
  } else {
    queryErrors.add(1);
  }

  sleep(0.5);

  // Test: Get articles/posts
  const articlesRes = http.get(
    `${apiBaseUrl}/api/articles?page=0&size=10`,
    params
  );

  queryDuration.add(articlesRes.timings.duration);

  const articleSuccess = check(articlesRes, {
    'articles status is 200': (r) => r.status === 200,
    'articles response time < 1000ms': (r) => r.timings.duration < 1000,
  });

  if (articleSuccess) {
    querySuccesses.add(1);
  } else {
    queryErrors.add(1);
  }

  sleep(1);
}
