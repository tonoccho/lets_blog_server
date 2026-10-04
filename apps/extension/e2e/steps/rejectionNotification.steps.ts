/** 差し戻し通知のステップ(issue #1347)。サーバー応答と通知はテスト側のフェイクで再現する。 */

import { Given, Then, When, World } from '../support/gherkin';
import {
  checkRejections,
  MyReview,
  RejectionCheckDeps,
} from '../../src/rejectionNotifier';

interface RejectionScope {
  loggedIn: boolean;
  projectId?: number;
  reviews: MyReview[];
  fetchCalls: number;
  notices: string[];
  errors: string[];
  stored: Map<string, unknown>;
}

function scope(world: World): RejectionScope {
  const holder = world as { rejection?: RejectionScope };
  holder.rejection ??= { loggedIn: true, reviews: [], fetchCalls: 0, notices: [], errors: [], stored: new Map() };
  return holder.rejection;
}

function depsOf(s: RejectionScope): RejectionCheckDeps {
  return {
    getProjectId: () => s.projectId,
    getAccessToken: async () => (s.loggedIn ? 'token' : undefined),
    getActor: async () => ({ email: 'writer@example.test', role: 'WRITER' }),
    fetchMyReviews: async () => {
      s.fetchCalls += 1;
      return s.reviews;
    },
    state: {
      get: (key) => s.stored.get(key) as string[] | undefined,
      update: async (key, value) => void s.stored.set(key, value),
    },
    notify: (message) => void s.notices.push(message),
    reportError: (message) => void s.errors.push(message),
  };
}

Given('ログイン済みで、プロジェクト {int} を選択している', (world, projectId) => {
  const s = scope(world);
  s.loggedIn = true;
  s.projectId = Number(projectId);
});

Given('ログアウトしている', (world) => {
  scope(world).loggedIn = false;
});

Given('プロジェクトを選択していない', (world) => {
  scope(world).projectId = undefined;
});

Given('サーバーは記事 {string} が差し戻され、指摘事項が {string} だと答える', (world, slug, comment) => {
  scope(world).reviews = [
    {
      prNumber: 7,
      articleSlug: slug,
      state: 'CHANGES_REQUESTED',
      submittedAt: '2026-10-01T00:00:00',
      rejectComment: comment,
      rejectedAt: '2026-10-02T03:04:05',
    },
  ];
});

Given('サーバーは記事 {string} が提出済みで、差し戻されていないと答える', (world, slug) => {
  scope(world).reviews = [
    {
      prNumber: 7,
      articleSlug: slug,
      state: 'SUBMITTED',
      submittedAt: '2026-10-01T00:00:00',
      rejectComment: null,
      rejectedAt: null,
    },
  ];
});

When('差し戻しを確認する', async (world) => {
  await checkRejections(depsOf(scope(world)));
});

When('手動確認コマンドを実行する', async (world) => {
  await checkRejections(depsOf(scope(world)), { manual: true });
});

Then('通知が {int} 件表示される', (world, count) => {
  expect(scope(world).notices).toHaveLength(Number(count));
});

Then('通知は表示されない', (world) => {
  expect(scope(world).notices).toEqual([]);
});

Then('通知に {string} と {string} が含まれる', (world, a, b) => {
  const [message] = scope(world).notices;
  expect(message).toContain(a);
  expect(message).toContain(b);
});

Then('サーバーへの確認が {int} 回行われている', (world, count) => {
  expect(scope(world).fetchCalls).toBe(Number(count));
});

Then('サーバーへの確認は行われていない', (world) => {
  expect(scope(world).fetchCalls).toBe(0);
});

Then('通知もエラー通知も表示されない', (world) => {
  expect(scope(world).notices).toEqual([]);
  expect(scope(world).errors).toEqual([]);
});
