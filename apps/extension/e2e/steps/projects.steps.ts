/** プロジェクト・サイト選択のステップ(issue #942 / AT-16)。 */

import { Given, Then, When } from '../support/gherkin';
import { ctx, w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { getProjectId, requireProjectId, setProjectId } from '../../src/config';
import { FIXTURE_PROJECT_SLUG } from '../support/api';

When('プロジェクト一覧を取得する', async (world) => {
  const scope = w(world);
  (scope as { projects?: unknown }).projects = await apiClient.listProjects(scope.token, scope.actor);
});

Then('受け入れテスト用のプロジェクトが一覧に含まれる', (world) => {
  const projects = (world as { projects?: { slug: string }[] }).projects ?? [];
  if (!projects.some((p) => p.slug === FIXTURE_PROJECT_SLUG)) {
    throw new Error(`一覧に ${FIXTURE_PROJECT_SLUG} がありません: ${JSON.stringify(projects)}`);
  }
});

Given('受け入れテスト用のプロジェクトを選択する', async (world) => {
  await setProjectId(ctx(world), w(world).project.id);
});

Then('選択したプロジェクトが次回以降も選択済みとして復元される', (world) => {
  const scope = w(world);
  const stored = getProjectId(ctx(world));
  if (stored !== scope.project.id) {
    throw new Error(`選択が保存されていません: ${String(stored)} != ${scope.project.id}`);
  }
  if (requireProjectId(ctx(world)) !== scope.project.id) {
    throw new Error('requireProjectId が選択済みのプロジェクトを返しません');
  }
});

When('選択中のプロジェクトの詳細を取得する', async (world) => {
  const scope = w(world);
  (scope as { detail?: unknown }).detail = await apiClient.getProject(
    scope.token,
    scope.actor,
    requireProjectId(ctx(world))
  );
});

Then('プロジェクト詳細にテスト環境のサイトが含まれる', (world) => {
  const detail = (world as { detail?: { testSite?: { id: number; siteKey: string } | null } }).detail;
  if (!detail?.testSite) {
    throw new Error(`テスト環境のサイトが紐付いていません: ${JSON.stringify(detail)}`);
  }
  (world as { boundSiteKey?: string }).boundSiteKey = detail.testSite.siteKey;
});

Then('サイト一覧に同じサイトが含まれる', async (world) => {
  const scope = w(world);
  const sites = await apiClient.listSites(scope.token, scope.actor);
  const expected = (world as { boundSiteKey?: string }).boundSiteKey;
  if (!sites.some((s) => s.siteKey === expected)) {
    throw new Error(`サイト一覧に ${String(expected)} がありません: ${JSON.stringify(sites)}`);
  }
});
