import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const apiBaseUrl = __ENV.API_BASE_URL || 'http://localhost:8080';
const projectId = __ENV.PROJECT_ID || '1';

const imageDuration = new Trend('image_generation_duration');
const imageErrors = new Counter('image_generation_errors');
const imageSuccesses = new Counter('image_generation_successes');

export const options = {
  stages: [
    { duration: '20s', target: 3 },   // Ramp-up to 3 users
    { duration: '1m', target: 5 },    // Ramp-up to 5 users
    { duration: '1m30s', target: 5 }, // Stay at 5 users
    { duration: '30s', target: 0 },   // Ramp-down to 0
  ],
  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed': ['rate<0.15'],
    'image_generation_errors': ['count<10'],
  },
};

export default function () {
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Fetch list of generated images
  const listRes = http.get(
    `${apiBaseUrl}/api/projects/${projectId}/generated-images?page=0&size=10`,
    params
  );

  check(listRes, {
    'list status is 200': (r) => r.status === 200,
  });

  // Get a specific image if available
  if (listRes.status === 200) {
    const data = JSON.parse(listRes.body);
    if (data.content && data.content.length > 0) {
      const imageId = data.content[0].id;

      const getRes = http.get(
        `${apiBaseUrl}/api/generated-images/${imageId}`,
        params
      );

      imageDuration.add(getRes.timings.duration);

      const success = check(getRes, {
        'get status is 200': (r) => r.status === 200,
        'response time < 1000ms': (r) => r.timings.duration < 1000,
        'has image data': (r) => r.body.length > 0,
      });

      if (success) {
        imageSuccesses.add(1);
      } else {
        imageErrors.add(1);
      }
    }
  }

  sleep(2);
}
