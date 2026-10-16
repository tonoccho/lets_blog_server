/**
 * @jest-environment node
 */

/**
 * 一括管理(カテゴリ・タグ・プラグイン・テーマ・ポスト/ページ)の Server Action の検証(#1836)。
 * `../actions` の実体を直接importし、`@/lib/apiClient`・`@/lib/session` も実物を通す。
 * 差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, form, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import {
  applyToEnvironmentAction,
  deletePostEverywhereAction,
  deleteSlugEverywhereAction,
  deleteTermEverywhereAction,
  editTermAndSyncAction,
  fetchPostComparisonAction,
  fetchPostStatusesAction,
  fetchStatusComparisonAction,
  fetchTermComparisonAction,
  reconcileStateAction,
  syncAllTermsToMasterAction,
  syncTermToMasterAction,
  updatePostStatusEverywhereAction,
} from '../actions';

const BULK = '/api/projects/7/bulk-management';
const LOG = { environment: 'test', status: 'SUCCESS' };

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe('applyToEnvironmentAction', () => {
  const APPLY = `POST ${BULK}/apply`;
  let backend: jest.Mock;
  beforeEach(() => {
    backend = installBackend({ [APPLY]: () => jsonResponse(LOG) });
  });

  it.each([
    ['環境が無い', form({ operationType: 'PLUGIN_INSTALL', value: 'a' }), '対象環境を選択してください。'],
    ['操作種別が無い', form({ environment: 'test', value: 'a' }), '操作種別を選択してください。'],
    ['削除の対象が無い(カテゴリ)', form({ environment: 'test', operationType: 'CATEGORY_DELETE' }), '削除対象を選択してください。'],
    ['削除の対象が無い(タグ)', form({ environment: 'test', operationType: 'TAG_DELETE' }), '削除対象を選択してください。'],
    ['作成の名前が無い', form({ environment: 'test', operationType: 'CATEGORY_CREATE', categorySlug: 's' }), '名前とスラッグを入力してください。'],
    ['作成のスラッグが無い', form({ environment: 'test', operationType: 'TAG_CREATE', value: 'n' }), '名前とスラッグを入力してください。'],
    ['編集の対象が無い(カテゴリ)', form({ environment: 'test', operationType: 'CATEGORY_EDIT', value: 'n', categorySlug: 's' }), '編集対象を選択してください。'],
    ['編集の対象が無い(タグ)', form({ environment: 'test', operationType: 'TAG_EDIT', value: 'n', categorySlug: 's' }), '編集対象を選択してください。'],
    ['インストールの slug が無い', form({ environment: 'test', operationType: 'PLUGIN_INSTALL', value: '  ' }), 'slugを入力してください。'],
    ['フォームが空', new FormData(), '対象環境を選択してください。'],
  ])('%s ならバックエンドへ送らずエラーを返す', async (_label, data, message) => {
    await expect(applyToEnvironmentAction(7, {}, data)).resolves.toEqual({ error: message });
    expect(requestCount(backend)).toBe(0);
  });

  it('カテゴリの作成は、すべての項目をトリムして送り、結果を返して再検証する', async () => {
    const result = await applyToEnvironmentAction(
      7,
      {},
      form({
        environment: 'test',
        operationType: 'CATEGORY_CREATE',
        value: ' お知らせ ',
        categorySlug: ' news ',
        categoryParentSlug: ' parent ',
        categoryDescription: ' 説明 ',
      })
    );

    expect(bodies(backend, APPLY)).toEqual([
      {
        environment: 'test',
        operationType: 'CATEGORY_CREATE',
        value: 'お知らせ',
        categorySlug: 'news',
        categoryParentSlug: 'parent',
        categoryDescription: '説明',
      },
    ]);
    expect(result).toEqual({ success: true, results: [LOG] });
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('カテゴリの編集は、対象のスラッグも送る', async () => {
    await applyToEnvironmentAction(
      7,
      {},
      form({ environment: 'test', operationType: 'CATEGORY_EDIT', value: 'n', categorySlug: 's', categoryTargetSlug: 'old' })
    );

    expect(bodies(backend, APPLY)).toEqual([
      { environment: 'test', operationType: 'CATEGORY_EDIT', value: 'n', categorySlug: 's', categoryTargetSlug: 'old' },
    ]);
  });

  it('削除は、対象のスラッグだけで送れる(名前は不要)', async () => {
    await applyToEnvironmentAction(7, {}, form({ environment: 'test', operationType: 'TAG_DELETE', categoryTargetSlug: 'old' }));

    expect(bodies(backend, APPLY)).toEqual([{ environment: 'test', operationType: 'TAG_DELETE', categoryTargetSlug: 'old' }]);
  });

  it('プラグインのインストールは slug(値)だけで送る', async () => {
    await applyToEnvironmentAction(7, {}, form({ environment: 'production', operationType: 'PLUGIN_INSTALL', value: ' akismet ' }));

    expect(bodies(backend, APPLY)).toEqual([{ environment: 'production', operationType: 'PLUGIN_INSTALL', value: 'akismet' }]);
  });

  it('バックエンドが失敗したらそのメッセージを返し、再検証しない(Error でない失敗も文字列にする)', async () => {
    installBackend({ [APPLY]: failWith(500, 'boom') });
    await expect(
      applyToEnvironmentAction(7, {}, form({ environment: 'test', operationType: 'PLUGIN_INSTALL', value: 'a' }))
    ).resolves.toEqual({ error: 'APIエラー (500): boom' });

    failFetchWith('plain');
    await expect(
      applyToEnvironmentAction(7, {}, form({ environment: 'test', operationType: 'PLUGIN_INSTALL', value: 'a' }))
    ).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(applyToEnvironmentAction(7, {}, form({ environment: 'test', operationType: 'PLUGIN_INSTALL', value: 'a' }))).rejects.toThrow(
      'NEXT_REDIRECT:/'
    );
    expect(requestCount(backend)).toBe(0);
  });
});

describe.each([
  ['category', 'categories'],
  ['tag', 'tags'],
] as const)('%s の一括管理アクション', (kind, path) => {
  const SYNC = `POST ${BULK}/${path}/sync`;
  const DELETE_ALL = `POST ${BULK}/${path}/delete-all`;
  const EDIT_SYNC = `POST ${BULK}/${path}/edit-sync`;
  const SYNC_ALL = `POST ${BULK}/${path}/sync-all`;
  const COMPARISON = `GET ${BULK}/${path}/comparison`;
  let backend: jest.Mock;

  beforeEach(() => {
    backend = installBackend({
      [SYNC]: () => jsonResponse([LOG]),
      [DELETE_ALL]: () => jsonResponse([LOG]),
      [EDIT_SYNC]: () => jsonResponse([LOG]),
      [SYNC_ALL]: () => jsonResponse([LOG]),
      [COMPARISON]: () => jsonResponse({ items: [], page: 2, size: 20, totalCount: 0, masterEnvironment: 'test' }),
    });
  });

  it('syncTermToMasterAction は slug を送り、結果を返して再検証する', async () => {
    await expect(syncTermToMasterAction(7, kind, 'news')).resolves.toEqual({ success: true, results: [LOG] });
    expect(bodies(backend, SYNC)).toEqual([{ slug: 'news' }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('deleteTermEverywhereAction は slug を送り、結果を返して再検証する', async () => {
    await expect(deleteTermEverywhereAction(7, kind, 'news')).resolves.toEqual({ success: true, results: [LOG] });
    expect(bodies(backend, DELETE_ALL)).toEqual([{ slug: 'news' }]);
  });

  it('editTermAndSyncAction は編集内容を送り、結果を返して再検証する', async () => {
    const input = { targetSlug: 'old', value: 'n', slug: 's', parentSlug: 'p', description: 'd' };
    await expect(editTermAndSyncAction(7, kind, input)).resolves.toEqual({ success: true, results: [LOG] });
    expect(bodies(backend, EDIT_SYNC)).toEqual([input]);
  });

  it('syncAllTermsToMasterAction は結果を返して再検証する', async () => {
    await expect(syncAllTermsToMasterAction(7, kind)).resolves.toEqual({ success: true, results: [LOG] });
    expect(requestCount(backend)).toBe(1);
  });

  it('fetchTermComparisonAction は指定のページの一覧を返す', async () => {
    await expect(fetchTermComparisonAction(7, kind, 2)).resolves.toMatchObject({ page: 2 });
    expect(backend.mock.calls[0][0]).toContain(`${path}/comparison?page=2`);
  });

  it.each([
    ['syncTermToMasterAction', () => syncTermToMasterAction(7, kind, 'news'), SYNC],
    ['deleteTermEverywhereAction', () => deleteTermEverywhereAction(7, kind, 'news'), DELETE_ALL],
    ['editTermAndSyncAction', () => editTermAndSyncAction(7, kind, { targetSlug: 'o', value: 'n', slug: 's' }), EDIT_SYNC],
    ['syncAllTermsToMasterAction', () => syncAllTermsToMasterAction(7, kind), SYNC_ALL],
  ])('%s はバックエンドが失敗したらエラーを返し、再検証しない(Error でない失敗も文字列にする)', async (_name, run, route) => {
    installBackend({ [route]: failWith(500, 'boom') });
    await expect(run()).resolves.toEqual({ error: 'APIエラー (500): boom' });

    failFetchWith('plain');
    await expect(run()).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

describe.each([
  ['plugin', 'plugins'],
  ['theme', 'themes'],
] as const)('%s の一括管理アクション', (kind, path) => {
  const RECONCILE = `POST ${BULK}/${path}/reconcile`;
  const DELETE_ALL = `POST ${BULK}/${path}/delete-all`;
  const COMPARISON = `GET ${BULK}/${path}/comparison`;
  let backend: jest.Mock;

  beforeEach(() => {
    backend = installBackend({
      [RECONCILE]: () => jsonResponse([LOG]),
      [DELETE_ALL]: () => jsonResponse([LOG]),
      [COMPARISON]: () => jsonResponse({ items: [], page: 1, size: 20, totalCount: 0, masterEnvironment: 'test' }),
    });
  });

  it('reconcileStateAction は変更内容を送り、結果を返して再検証する', async () => {
    const changes = [{ environment: 'test' as const, desiredStatus: 'ACTIVE' as const }];
    await expect(reconcileStateAction(7, kind, 'akismet', changes)).resolves.toEqual({ success: true, results: [LOG] });
    expect(bodies(backend, RECONCILE)).toEqual([{ slug: 'akismet', changes }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('deleteSlugEverywhereAction は slug を送り、結果を返して再検証する', async () => {
    await expect(deleteSlugEverywhereAction(7, kind, 'akismet')).resolves.toEqual({ success: true, results: [LOG] });
    expect(bodies(backend, DELETE_ALL)).toEqual([{ slug: 'akismet' }]);
  });

  it('fetchStatusComparisonAction は指定のページの一覧を返す', async () => {
    await expect(fetchStatusComparisonAction(7, kind, 1)).resolves.toMatchObject({ page: 1 });
    expect(backend.mock.calls[0][0]).toContain(`${path}/comparison?page=1`);
  });

  it.each([
    ['reconcileStateAction', () => reconcileStateAction(7, kind, 'a', []), RECONCILE],
    ['deleteSlugEverywhereAction', () => deleteSlugEverywhereAction(7, kind, 'a'), DELETE_ALL],
  ])('%s はバックエンドが失敗したらエラーを返し、再検証しない(Error でない失敗も文字列にする)', async (_name, run, route) => {
    installBackend({ [route]: failWith(500, 'boom') });
    await expect(run()).resolves.toEqual({ error: 'APIエラー (500): boom' });

    failFetchWith('plain');
    await expect(run()).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});

describe('ポスト/ページの一括管理アクション', () => {
  const POSTS = `${BULK}/posts`;

  it('fetchPostComparisonAction は種別とページを指定して一覧を返す', async () => {
    const backend = installBackend({
      [`GET ${POSTS}/comparison`]: () => jsonResponse({ items: [], page: 3, size: 20, totalCount: 0, postType: 'page' }),
    });
    await expect(fetchPostComparisonAction(7, 'page', 3)).resolves.toMatchObject({ page: 3 });
    expect(backend.mock.calls[0][0]).toContain('/posts/comparison?postType=page&page=3');
  });

  it('fetchPostStatusesAction はステータスの選択肢を返す', async () => {
    installBackend({ 'GET /api/metadata/post-statuses': () => jsonResponse([{ value: 'draft', label: '下書き' }]) });
    await expect(fetchPostStatusesAction()).resolves.toEqual([{ value: 'draft', label: '下書き' }]);
  });

  it('deletePostEverywhereAction は種別と slug を送り、再検証する', async () => {
    const backend = installBackend({ [`POST ${POSTS}/delete-all`]: () => jsonResponse([]) });
    await expect(deletePostEverywhereAction(7, 'page', 'hello')).resolves.toEqual({});
    expect(bodies(backend, `POST ${POSTS}/delete-all`)).toEqual([{ slug: 'hello' }]);
    expect(backend.mock.calls[0][0]).toContain('delete-all?postType=page');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('updatePostStatusEverywhereAction は種別・slug・ステータスを送り、再検証する', async () => {
    const backend = installBackend({ [`POST ${POSTS}/status-update`]: () => jsonResponse([]) });
    await expect(updatePostStatusEverywhereAction(7, 'post', 'hello', 'draft')).resolves.toEqual({});
    expect(bodies(backend, `POST ${POSTS}/status-update`)).toEqual([{ slug: 'hello', status: 'draft' }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it.each([
    ['deletePostEverywhereAction', () => deletePostEverywhereAction(7, 'post', 'a'), `POST ${POSTS}/delete-all`],
    ['updatePostStatusEverywhereAction', () => updatePostStatusEverywhereAction(7, 'post', 'a', 'draft'), `POST ${POSTS}/status-update`],
  ])('%s はバックエンドが失敗したらエラーを返し、再検証しない(Error でない失敗も文字列にする)', async (_name, run, route) => {
    installBackend({ [route]: failWith(500, 'boom') });
    await expect(run()).resolves.toEqual({ error: 'APIエラー (500): boom' });

    failFetchWith('plain');
    await expect(run()).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
