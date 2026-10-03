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
 * issue #1334 で、publishing-service(#1333)が使う Pull Request / contents / comments API を足した。
 *   GET    /repos/{owner}/{repo}
 *   GET    /repos/{owner}/{repo}/pulls?state=
 *   POST   /repos/{owner}/{repo}/pulls
 *   GET    /repos/{owner}/{repo}/pulls/{number}
 *   GET    /repos/{owner}/{repo}/branches/{branch}         (ブランチが在れば200、無ければ404。#1339)
 *   GET    /repos/{owner}/{repo}/pulls/{number}/files
 *   PUT    /repos/{owner}/{repo}/pulls/{number}/merge
 *   DELETE /repos/{owner}/{repo}/git/refs/heads/{branch}
 *   GET    /repos/{owner}/{repo}/contents/{path}?ref=      (ref はブランチ名または head の sha)
 *   PUT    /repos/{owner}/{repo}/contents/{path}           (branch へファイルを置く/更新する。head の sha が進む)
 *   GET    /repos/{owner}/{repo}/git/blobs/{sha}           (Accept: application/vnd.github.raw なら生のバイト列)
 *   GET|POST /repos/{owner}/{repo}/issues/{number}/comments   (PR も Issue として扱う)
 *   GET      /repos/{owner}/{repo}/issues/comments/{id}        (コメント1件。#1344)
 * head のブランチ名に `-conflict-` を含む PR はコンフリクトとして扱う(詳細で mergeable が false、マージは 405。#1343)。
 * PR を作るとき head ブランチがスタブに無ければ、空のブランチを作る(実 GitHub なら 422 だが、
 * スタブは受け入れテストが毎回一意な head を使えるよう寛容にしている)。
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
const crypto = require('node:crypto');
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

const DEFAULT_BRANCH = 'main';
const PR_HTML_BASE = 'https://github.com/e2e-stub/acceptance/pull';
/** contents API が content を返さない境界(GitHub は 1MB 超で content を返さない)。 */
const CONTENTS_LIMIT = 1024 * 1024;

const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

/** git の blob sha(sha1("blob <size>\0" + content))。同じ内容なら常に同じ sha になる。 */
function blobSha(content) {
  return crypto.createHash('sha1').update(`blob ${content.length}\0`).update(content).digest('hex');
}

function commitSha(seed) {
  return crypto.createHash('sha1').update(`commit ${seed}`).digest('hex');
}

function file(content) {
  return { content, sha: blobSha(content) };
}

/**
 * PR・ブランチ・コメントのシード。ブランチは path → { content, sha } の Map。
 * `article/e2e-sample` には記事本文・PNG・1MB 超の画像を置く(1MB 超は実バイト列を持つが、
 * 中身は同じバイトの繰り返しで決定的)。
 */
function seedRepo() {
  const branches = new Map([
    [DEFAULT_BRANCH, new Map([['README.md', file(Buffer.from('# e2e-stub/acceptance\n', 'utf8'))]])],
    ['article/e2e-sample', new Map([
      ['articles/e2e-sample/article.md', file(Buffer.from('# E2Eスタブの記事\n\n本文(スタブ)\n', 'utf8'))],
      ['articles/e2e-sample/assets/cover.png', file(PNG_1X1)],
      ['articles/e2e-sample/assets/large.png', file(Buffer.alloc(CONTENTS_LIMIT + 1024, 0x61))],
    ])],
  ]);
  const prs = new Map([
    [201, { number: 201, title: 'E2Eスタブ: 記事サンプル', body: '記事サンプルのPR(スタブ)', state: 'open',
            head: 'article/e2e-sample', base: DEFAULT_BRANCH, merged: false, merge_commit_sha: null }],
    [202, { number: 202, title: 'E2Eスタブ: 公開済みの記事', body: '公開済みのPR(スタブ)', state: 'closed',
            head: 'article/e2e-published', base: DEFAULT_BRANCH, merged: true,
            merge_commit_sha: commitSha('merged-202') }],
  ]);
  return { branches, prs, comments: [], commits: new Map() };
}

let repo = seedRepo();

/** ブランチへ PUT contents するたびに増える。head の sha を「コミットが積まれた」ことで変えるため。 */
function headSha(pr) {
  return commitSha(`${pr.head}${(repo.commits.get(pr.head) || 0) > 0 ? `#${repo.commits.get(pr.head)}` : ''}`);
}

/** contents の ref(ブランチ名、または PR の head sha)からブランチ名を引く。 */
function resolveBranch(ref) {
  if (repo.branches.has(ref)) return ref;
  for (const pr of repo.prs.values()) {
    if (headSha(pr) === ref) return pr.head;
  }
  return undefined;
}

/** head のブランチ名に `-conflict-` を含む PR は、受け入れテストがコンフリクトを再現するためのもの(#1343)。 */
function isConflicting(pr) {
  return pr.head.includes('-conflict-');
}

function prToApi(pr) {
  return {
    number: pr.number,
    title: pr.title,
    body: pr.body,
    state: pr.state,
    html_url: `${PR_HTML_BASE}/${pr.number}`,
    // 実 GitHub は PR の created_at を必ず返す。PR 一覧画面の作成日時表示(#1340)を確かめるため、
    // 番号から決まる固定の時刻を返す(実行のたびに変わらない)。
    created_at: new Date(Date.UTC(2026, 8, 1) + pr.number * 1000).toISOString(),
    user: { login: 'e2e-stub-user' },
    draft: false,
    head: { ref: pr.head, sha: headSha(pr), label: `e2e-stub:${pr.head}` },
    base: { ref: pr.base, sha: commitSha(`base-${pr.base}`) },
    merged: pr.merged,
    mergeable: pr.state === 'open' && !isConflicting(pr),
    merge_commit_sha: pr.merge_commit_sha,
  };
}

/** head にあって base に無い(または内容が違う)ファイル。 */
function changedFiles(pr) {
  const head = repo.branches.get(pr.head) || new Map();
  const base = repo.branches.get(pr.base) || new Map();
  return [...head.entries()]
    .filter(([path, f]) => !base.has(path) || base.get(path).sha !== f.sha)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([path, f]) => ({
      filename: path,
      status: base.has(path) ? 'modified' : 'added',
      sha: f.sha,
      additions: 1,
      deletions: 0,
      changes: 1,
      blob_url: `https://github.com/e2e-stub/acceptance/blob/${pr.head}/${path}`,
      raw_url: `https://github.com/e2e-stub/acceptance/raw/${pr.head}/${path}`,
    }));
}

function commentToApi(c) {
  return {
    id: c.id,
    body: c.body,
    user: { login: 'e2e-stub-user' },
    // 時刻も採番と同様に決定的にする(id から算出)。
    created_at: new Date(Date.UTC(2026, 0, 1) + c.id * 1000).toISOString(),
    html_url: `${HTML_BASE}/${c.number}#issuecomment-${c.id}`,
  };
}

function parseJson(body) {
  try {
    return JSON.parse(body || '{}');
  } catch {
    return {};
  }
}

const NOT_FOUND = { message: 'Not Found', documentation_url: 'stub' };

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
    repo = seedRepo();
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

    const repoMatch = /^\/repos\/[^/]+\/[^/]+$/.exec(pathname);
    if (repoMatch && method === 'GET') {
      sendJson(res, 200, {
        name: 'acceptance', full_name: 'e2e-stub/acceptance', private: false, default_branch: DEFAULT_BRANCH,
      });
      return true;
    }

    const branchMatch = /^\/repos\/[^/]+\/[^/]+\/branches\/(.+)$/.exec(pathname);
    if (branchMatch && method === 'GET') {
      const branch = decodeURIComponent(branchMatch[1]);
      if (!repo.branches.has(branch)) {
        sendJson(res, 404, { message: 'Branch not found', documentation_url: 'stub' });
        return true;
      }
      sendJson(res, 200, { name: branch, protected: branch === DEFAULT_BRANCH });
      return true;
    }

    const pullsMatch = /^\/repos\/[^/]+\/[^/]+\/pulls$/.exec(pathname);
    if (pullsMatch && method === 'GET') {
      const state = query.get('state') || 'open';
      sendJson(res, 200, [...repo.prs.values()]
        .filter((p) => state === 'all' || p.state === state)
        .sort((a, b) => a.number - b.number)
        .map(prToApi));
      return true;
    }
    if (pullsMatch && method === 'POST') {
      const forbidden = writeForbidden(req);
      if (forbidden) {
        sendJson(res, forbidden.status, forbidden.body);
        return true;
      }
      const payload = parseJson(body);
      const head = payload.head || '(no head)';
      if (!repo.branches.has(head)) repo.branches.set(head, new Map());
      // 決定的な採番: 受信順ではなく現在の最大値+1。
      const number = Math.max(...repo.prs.keys()) + 1;
      const created = {
        number, title: payload.title || '(no title)', body: payload.body || '', state: 'open',
        head, base: payload.base || DEFAULT_BRANCH, merged: false, merge_commit_sha: null,
      };
      repo.prs.set(number, created);
      sendJson(res, 201, prToApi(created));
      return true;
    }

    const pullMatch = /^\/repos\/[^/]+\/[^/]+\/pulls\/(\d+)(\/files|\/merge)?$/.exec(pathname);
    if (pullMatch) {
      const pr = repo.prs.get(Number(pullMatch[1]));
      if (!pr) {
        sendJson(res, 404, NOT_FOUND);
        return true;
      }
      if (method === 'GET' && !pullMatch[2]) {
        sendJson(res, 200, prToApi(pr));
        return true;
      }
      if (method === 'GET' && pullMatch[2] === '/files') {
        sendJson(res, 200, changedFiles(pr));
        return true;
      }
      if (method === 'PUT' && pullMatch[2] === '/merge') {
        const forbidden = writeForbidden(req);
        if (forbidden) {
          sendJson(res, forbidden.status, forbidden.body);
          return true;
        }
        if (pr.state !== 'open' || isConflicting(pr)) {
          sendJson(res, 405, { message: 'Pull Request is not mergeable', documentation_url: 'stub' });
          return true;
        }
        const base = repo.branches.get(pr.base) || new Map();
        for (const [path, f] of repo.branches.get(pr.head) || []) base.set(path, f);
        repo.branches.set(pr.base, base);
        pr.state = 'closed';
        pr.merged = true;
        pr.merge_commit_sha = commitSha(`merge-${pr.number}`);
        sendJson(res, 200, { sha: pr.merge_commit_sha, merged: true, message: 'Pull Request successfully merged' });
        return true;
      }
    }

    const refMatch = /^\/repos\/[^/]+\/[^/]+\/git\/refs\/heads\/(.+)$/.exec(pathname);
    if (refMatch && method === 'DELETE') {
      const forbidden = writeForbidden(req);
      if (forbidden) {
        sendJson(res, forbidden.status, forbidden.body);
        return true;
      }
      const branch = decodeURIComponent(refMatch[1]);
      if (branch === DEFAULT_BRANCH || !repo.branches.delete(branch)) {
        sendJson(res, 422, { message: 'Reference does not exist', documentation_url: 'stub' });
        return true;
      }
      res.writeHead(204);
      res.end();
      return true;
    }

    const contentsMatch = /^\/repos\/[^/]+\/[^/]+\/contents\/(.+)$/.exec(pathname);
    if (contentsMatch && method === 'GET') {
      const path = decodeURIComponent(contentsMatch[1]);
      const branch = resolveBranch(query.get('ref') || DEFAULT_BRANCH);
      const found = (branch && repo.branches.get(branch) || new Map()).get(path);
      if (!found) {
        sendJson(res, 404, NOT_FOUND);
        return true;
      }
      const big = found.content.length > CONTENTS_LIMIT;
      sendJson(res, 200, {
        type: 'file', name: path.split('/').pop(), path, sha: found.sha, size: found.content.length,
        encoding: big ? 'none' : 'base64',
        content: big ? null : found.content.toString('base64'),
      });
      return true;
    }
    if (contentsMatch && method === 'PUT') {
      const forbidden = writeForbidden(req);
      if (forbidden) {
        sendJson(res, forbidden.status, forbidden.body);
        return true;
      }
      const path = decodeURIComponent(contentsMatch[1]);
      const payload = parseJson(body);
      const branch = payload.branch || DEFAULT_BRANCH;
      if (!repo.branches.has(branch)) {
        sendJson(res, 404, NOT_FOUND);
        return true;
      }
      const files = repo.branches.get(branch);
      const existed = files.has(path);
      const created = file(Buffer.from(payload.content || '', 'base64'));
      files.set(path, created);
      repo.commits.set(branch, (repo.commits.get(branch) || 0) + 1);
      sendJson(res, existed ? 200 : 201, {
        content: { type: 'file', name: path.split('/').pop(), path, sha: created.sha, size: created.content.length },
        commit: { sha: commitSha(`${branch}#${repo.commits.get(branch)}`), message: payload.message || '' },
      });
      return true;
    }

    const blobMatch = /^\/repos\/[^/]+\/[^/]+\/git\/blobs\/([0-9a-f]+)$/.exec(pathname);
    if (blobMatch && method === 'GET') {
      for (const files of repo.branches.values()) {
        for (const f of files.values()) {
          if (f.sha === blobMatch[1]) {
            // 1MB 超の取得はこの経路を Accept: application/vnd.github.raw で使う(生のバイト列を返す)。
            if (String(req.headers.accept || '').includes('application/vnd.github.raw')) {
              res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': f.content.length });
              res.end(f.content);
              return true;
            }
            sendJson(res, 200, {
              sha: f.sha, size: f.content.length, encoding: 'base64', content: f.content.toString('base64'),
            });
            return true;
          }
        }
      }
      sendJson(res, 404, NOT_FOUND);
      return true;
    }

    const commentsMatch = /^\/repos\/[^/]+\/[^/]+\/issues\/(\d+)\/comments$/.exec(pathname);
    if (commentsMatch) {
      const number = Number(commentsMatch[1]);
      if (!issues.has(number) && !repo.prs.has(number)) {
        sendJson(res, 404, NOT_FOUND);
        return true;
      }
      if (method === 'GET') {
        sendJson(res, 200, repo.comments.filter((c) => c.number === number).map(commentToApi));
        return true;
      }
      if (method === 'POST') {
        const forbidden = writeForbidden(req);
        if (forbidden) {
          sendJson(res, forbidden.status, forbidden.body);
          return true;
        }
        // 決定的な採番: 現在の最大値+1(シードのコメントは無いので 5001 から)。
        const id = Math.max(5000, ...repo.comments.map((c) => c.id)) + 1;
        const comment = { id, number, body: parseJson(body).body || '' };
        repo.comments.push(comment);
        sendJson(res, 201, commentToApi(comment));
        return true;
      }
    }

    // コメント1件をIDで取る(publishing-service の差し戻し一覧が指摘の本文を引く。issue #1344)。
    const commentByIdMatch = /^\/repos\/[^/]+\/[^/]+\/issues\/comments\/(\d+)$/.exec(pathname);
    if (commentByIdMatch && method === 'GET') {
      const id = Number(commentByIdMatch[1]);
      const comment = repo.comments.find((c) => c.id === id);
      sendJson(res, comment ? 200 : 404, comment ? commentToApi(comment) : NOT_FOUND);
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
