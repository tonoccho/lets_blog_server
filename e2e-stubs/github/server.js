'use strict';
/**
 * GitHub REST API(issues)のスタブ(issue #928 / AT-2)。
 *
 * ai-service の GithubClient が叩くのは以下だけ。
 *   GET   /user
 *   GET   /repos/{owner}/{repo}/issues?state=&per_page=
 *   POST  /repos/{owner}/{repo}/issues
 *   GET   /repos/{owner}/{repo}/issues/{number}
 *   PATCH /repos/{owner}/{repo}/issues/{number}
 *
 * これをスタブ化する理由は、記事プラン(#935 / AT-9)の受け入れテストが**本リポジトリの
 * Issue を汚さずに**通るようにするため。実 GitHub へ向けると、テストのたびに Issue が
 * 作られ、担当者が書き換わる。
 *
 * 状態: 作成・更新した Issue はプロセス内に保持し、続くGETで読める。
 * ただし**採番は決定的**にする(受信順ではなく既定シードの最大値+1)。同じ手順を3回踏めば
 * 常に同じ番号になる。プロセスを再起動すれば初期状態に戻る。
 *
 * 認証・権限・レート制限の再現:
 *   Authorization: Bearer e2e-stub-invalid-token   → 401
 *   Authorization: Bearer e2e-stub-readonly-token  → 書き込み(POST/PATCH)で403
 *   Authorization: Bearer e2e-stub-ratelimited-token → 403 + X-RateLimit-Remaining: 0
 */
const { createStub } = require('../lib/stub');

const HTML_BASE = 'https://github.com/e2e-stub/acceptance/issues';

/** 決定的なシード。番号・タイトル・本文・担当者を固定する。 */
function seedIssues() {
  return new Map([
    [101, { number: 101, title: 'E2Eスタブ: 書く予定の記事A', body: 'Aの本文(スタブ)', state: 'open', assignees: [] }],
    [102, { number: 102, title: 'E2Eスタブ: 書く予定の記事B', body: 'Bの本文(スタブ)', state: 'open',
            assignees: [{ login: 'e2e-stub-user' }] }],
    [103, { number: 103, title: 'E2Eスタブ: 公開済みの記事C', body: 'Cの本文(スタブ)', state: 'closed', assignees: [] }],
  ]);
}

const issues = seedIssues();

function toApi(issue) {
  return {
    number: issue.number,
    title: issue.title,
    body: issue.body,
    state: issue.state,
    html_url: `${HTML_BASE}/${issue.number}`,
    assignees: issue.assignees,
    user: { login: 'e2e-stub-user' },
  };
}

function authFailure(req) {
  const auth = String(req.headers.authorization || '');
  const token = auth.replace(/^Bearer\s+/i, '');
  if (token === 'e2e-stub-invalid-token') {
    return { status: 401, headers: {}, body: { message: 'Bad credentials', documentation_url: 'stub' } };
  }
  if (token === 'e2e-stub-ratelimited-token') {
    return {
      status: 403,
      headers: { 'X-RateLimit-Limit': '5000', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '1756684800' },
      body: { message: 'API rate limit exceeded', documentation_url: 'stub' },
    };
  }
  return null;
}

function writeForbidden(req) {
  const token = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  if (token === 'e2e-stub-readonly-token') {
    return { status: 403, headers: {}, body: { message: 'Resource not accessible by personal access token' } };
  }
  return null;
}

createStub({
  name: 'github',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ message: `[${name}] forced ${status}`, documentation_url: 'stub' }),
  // 書き込みで状態が変わるスタブなので、reset でシードへ戻す。
  onReset: () => {
    issues.clear();
    for (const [number, issue] of seedIssues()) issues.set(number, issue);
  },
  async handle({ method, pathname, query, body, req, res, sendJson }) {
    const denied = authFailure(req);
    if (denied) {
      sendJson(res, denied.status, denied.body, denied.headers);
      return true;
    }

    if (method === 'GET' && pathname === '/user') {
      sendJson(res, 200, { login: 'e2e-stub-user', id: 1, type: 'User' });
      return true;
    }

    const listMatch = /^\/repos\/[^/]+\/[^/]+\/issues$/.exec(pathname);
    if (listMatch && method === 'GET') {
      const state = query.get('state') || 'open';
      const list = [...issues.values()]
        .filter((i) => state === 'all' || i.state === state)
        .sort((a, b) => a.number - b.number)
        .map(toApi);
      sendJson(res, 200, list);
      return true;
    }

    if (listMatch && method === 'POST') {
      const forbidden = writeForbidden(req);
      if (forbidden) {
        sendJson(res, forbidden.status, forbidden.body);
        return true;
      }
      let payload = {};
      try {
        payload = JSON.parse(body || '{}');
      } catch { /* 空ボディでも作る */ }
      // 決定的な採番: 受信順ではなく現在の最大値+1。
      const number = Math.max(...issues.keys()) + 1;
      const created = {
        number,
        title: payload.title || '(no title)',
        body: payload.body || '',
        state: 'open',
        assignees: [],
      };
      issues.set(number, created);
      sendJson(res, 201, toApi(created));
      return true;
    }

    const oneMatch = /^\/repos\/[^/]+\/[^/]+\/issues\/(\d+)$/.exec(pathname);
    if (oneMatch) {
      const number = Number(oneMatch[1]);
      const issue = issues.get(number);
      if (!issue) {
        sendJson(res, 404, { message: 'Not Found', documentation_url: 'stub' });
        return true;
      }
      if (method === 'GET') {
        sendJson(res, 200, toApi(issue));
        return true;
      }
      if (method === 'PATCH') {
        const forbidden = writeForbidden(req);
        if (forbidden) {
          sendJson(res, forbidden.status, forbidden.body);
          return true;
        }
        let payload = {};
        try {
          payload = JSON.parse(body || '{}');
        } catch { /* 空ボディなら変更なし */ }
        if (payload.title !== undefined) issue.title = payload.title;
        if (payload.body !== undefined) issue.body = payload.body;
        if (payload.state !== undefined) issue.state = payload.state;
        if (payload.assignees !== undefined) {
          issue.assignees = (payload.assignees || []).map((login) => ({ login }));
        }
        sendJson(res, 200, toApi(issue));
        return true;
      }
    }

    return false;
  },
});
