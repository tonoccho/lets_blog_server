/**
 * 記事計画の gateway の向こう側(content-service など)の代役(#1835)。
 *
 * 差し替えるのは `global.fetch`(外部との通信)だけ。応答はテストごとに `backend.<名前>` へ代入して変える。
 * 呼び出しは `backend.requests`("<METHOD> <パスと検索文字列>" を呼び出し順に)と `backend.bodies`(JSON の本文)で読める。
 */
import { installFetch, jsonResponse, type FetchRoutes } from "@/lib/__tests__/testUtils/serverRuntime";

type Respond = () => Response | Promise<Response>;

export interface PlanBackend {
  project: Respond;
  sessions: Respond;
  session: Respond;
  sessionByIssue: Respond;
  issueDescription: Respond;
  issues: (state: string) => Response | Promise<Response>;
  chat: Respond;
  suggestTitles: Respond;
  accept: Respond;
  suggestStructure: Respond;
  acceptStructure: Respond;
  profile: Respond;
  requests: string[];
  bodies: Record<string, unknown[]>;
}

export const PROJECT = { id: 7, name: "P", githubRepository: "o/r" };

export function installPlanBackend(extra: FetchRoutes = {}): PlanBackend {
  const backend: PlanBackend = {
    project: () => jsonResponse(PROJECT),
    sessions: () => jsonResponse([]),
    session: () => jsonResponse({ id: 1, title: "t", githubIssueNumber: null, history: [], createdAt: "2026-09-08T20:03:35Z", updatedAt: "2026-09-08T20:03:35Z" }),
    sessionByIssue: () => jsonResponse(null),
    issueDescription: () => jsonResponse({ body: "" }),
    issues: () => jsonResponse([]),
    chat: () => jsonResponse({ reply: "返答", sessionId: 11 }),
    suggestTitles: () => jsonResponse({ titles: ["案A", "案B"] }),
    accept: () => jsonResponse({ results: [] }),
    suggestStructure: () => jsonResponse({ structure: "# 構成" }),
    acceptStructure: () => jsonResponse({ issueNumber: 3, issueUrl: "https://github.com/o/r/issues/3" }),
    profile: () => jsonResponse({ id: 1, timezone: null }),
    requests: [],
    bodies: {},
  };
  const log = (key: string, init: RequestInit, path: string) => {
    backend.requests.push(`${init.method ?? "GET"} ${path}`);
    if (init.body) (backend.bodies[key] ??= []).push(JSON.parse(String(init.body)));
  };
  installFetch({
    "GET /api/projects/:id": () => backend.project(),
    "GET /api/projects/:id/article-plan/sessions": () => backend.sessions(),
    "GET /api/projects/:id/article-plan/sessions/by-issue/:n": (_u, _i, [, n]) => {
      backend.requests.push(`GET by-issue/${n}`);
      return backend.sessionByIssue();
    },
    "GET /api/projects/:id/article-plan/sessions/:sid": (_u, _i, [, sid]) => {
      backend.requests.push(`GET session/${sid}`);
      return backend.session();
    },
    "GET /api/projects/:id/article-plan/issues/:n/description": (_u, _i, [, n]) => {
      backend.requests.push(`GET description/${n}`);
      return backend.issueDescription();
    },
    "GET /api/projects/:id/article-plan/issues?state=open": () => {
      backend.requests.push("GET issues?state=open");
      return backend.issues("open");
    },
    "GET /api/projects/:id/article-plan/issues?state=closed": () => {
      backend.requests.push("GET issues?state=closed");
      return backend.issues("closed");
    },
    "GET /api/projects/:id/article-plan/issues?state=all": () => {
      backend.requests.push("GET issues?state=all");
      return backend.issues("all");
    },
    "POST /api/projects/:id/article-plan/chat": (_u, init) => {
      log("chat", init, "POST chat");
      return backend.chat();
    },
    "POST /api/projects/:id/article-plan/suggest-titles": (_u, init) => {
      log("suggestTitles", init, "POST suggest-titles");
      return backend.suggestTitles();
    },
    "POST /api/projects/:id/article-plan/accept": (_u, init) => {
      log("accept", init, "POST accept");
      return backend.accept();
    },
    "POST /api/projects/:id/article-plan/suggest-structure": (_u, init) => {
      log("suggestStructure", init, "POST suggest-structure");
      return backend.suggestStructure();
    },
    "POST /api/projects/:id/article-plan/issues/:n/accept-structure": (_u, init, [, n]) => {
      log("acceptStructure", init, `POST accept-structure/${n}`);
      return backend.acceptStructure();
    },
    "GET /api/identity/me": () => backend.profile(),
    ...extra,
  });
  return backend;
}
