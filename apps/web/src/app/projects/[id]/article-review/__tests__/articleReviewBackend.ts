/**
 * 記事レビューの gateway の向こう側(publishing-service)の代役(#1835)。
 *
 * 差し替えるのは `global.fetch`(外部との通信)だけ。応答はテストごとに `backend.start` などへ代入して変える。
 * 呼び出しは `backend.requests`(`"<METHOD> <パス>"` を呼び出し順に)と `backend.rejectBodies` で読める。
 */
import { installFetch, jsonResponse, type FetchRoutes } from "@/lib/__tests__/testUtils/serverRuntime";

export interface ReviewBackend {
  start: () => Response | Promise<Response>;
  approve: () => Response | Promise<Response>;
  reject: () => Response | Promise<Response>;
  requests: string[];
  rejectBodies: unknown[];
}

export function installReviewBackend(extra: FetchRoutes = {}): ReviewBackend {
  const backend: ReviewBackend = {
    start: () => jsonResponse({ prNumber: 201, state: "IN_REVIEW", testPostUrl: "https://t.example/a/" }),
    approve: () => jsonResponse({ prNumber: 201, state: "PUBLISHED", productionPostUrl: "https://prod.example/a/", branchDeleted: true }),
    reject: () => jsonResponse({ prNumber: 201, state: "CHANGES_REQUESTED", commentId: 5 }),
    requests: [],
    rejectBodies: [],
  };
  const record = (init: RequestInit, path: string) => backend.requests.push(`${init.method ?? "GET"} ${path}`);
  installFetch({
    "POST /api/projects/:id/article-review/pull-requests/:pr/review": (_u, init, [id, pr]) => {
      record(init, `/api/projects/${id}/article-review/pull-requests/${pr}/review`);
      return backend.start();
    },
    "POST /api/projects/:id/article-review/pull-requests/:pr/approve": (_u, init, [id, pr]) => {
      record(init, `/api/projects/${id}/article-review/pull-requests/${pr}/approve`);
      return backend.approve();
    },
    "POST /api/projects/:id/article-review/pull-requests/:pr/reject": (_u, init, [id, pr]) => {
      record(init, `/api/projects/${id}/article-review/pull-requests/${pr}/reject`);
      backend.rejectBodies.push(JSON.parse(String(init.body)));
      return backend.reject();
    },
    ...extra,
  });
  return backend;
}
