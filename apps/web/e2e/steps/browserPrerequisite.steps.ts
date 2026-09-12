import { Given, Then, When } from './fixtures';
import { expect } from '../support';
import {
  CHROMIUM_APT_PACKAGES,
  PREREQUISITE_ERROR_PREFIX,
  checkBrowsersLaunchable,
  describeBrowserLaunchFailure,
  parseProjectSelectionFromArgv,
  requiredBrowserNames,
  resolveExecutedProjects,
  type ProjectBrowserSelection,
} from '../browser-prerequisite';

/**
 * Playwright ブラウザの前提確認のステップ定義(issue #1045)。
 *
 * ここで検証するのは `apps/web/e2e/browser-prerequisite.ts` の**判定と文面**であって、
 * 製品の振る舞いではない。ブラウザが起動できないホストではブラウザ経路のシナリオが
 * 1本も走らないため、このシナリオ自身は `@api`(UI を使わない)にしてある。
 * ブラウザを起動できないホストでも、この土台の検証だけは動く必要がある。
 */

/** 直前に組み立てた前提エラーの説明。シナリオ内でステップ間を跨ぐので ctx に置く。 */
const DESCRIPTION = 'browserPrerequisiteDescription';
const BROWSERS = 'browserPrerequisiteBrowsers';
const REAL_LAUNCH = 'browserPrerequisiteRealLaunch';

When('ブラウザの起動が次のエラーで失敗する:', async ({ ctx }, raw: string) => {
  ctx[DESCRIPTION] = describeBrowserLaunchFailure('chromium', new Error(raw));
});

Then('前提確認は失敗する', async ({ ctx }) => {
  const description = ctx[DESCRIPTION] as string | undefined;
  expect(description, '前提確認が説明を返していない').toBeTruthy();
  expect(description).toContain(PREREQUISITE_ERROR_PREFIX);
});

Then('失敗の説明に {string} が含まれる', async ({ ctx }, needle: string) => {
  expect(ctx[DESCRIPTION] as string).toContain(needle);
});

Then('失敗の説明に {string} は含まれない', async ({ ctx }, needle: string) => {
  expect(ctx[DESCRIPTION] as string).not.toContain(needle);
});

Then('失敗の説明に apt で導入するパッケージが全て列挙されている', async ({ ctx }) => {
  const description = ctx[DESCRIPTION] as string;
  for (const pkg of CHROMIUM_APT_PACKAGES) {
    expect(description, `apt パッケージ ${pkg} が説明に無い`).toContain(pkg);
  }
});

/**
 * `"at-main=defaultBrowserType:chromium, firefox=browserName:firefox"` という書式を
 * `FullConfig['projects']` 相当の形へ戻す。`<プロジェクト>=` はブラウザ未指定を表す。
 *
 * `|deps:` を続けると `dependencies`(`playwright.config.ts` の同名フィールド)を表せる。
 * 複数の依存元は `+` で連ねる(例: `at-main=defaultBrowserType:chromium|deps:at-provision`)。
 */
function parseProjectSelection(spec: string): ProjectBrowserSelection[] {
  return spec.split(',').map((entry) => {
    const [name, rest] = entry.split('=').map((s) => s.trim());
    if (!rest) return { name, use: {} };
    const [value, depsSpec] = rest.split('|deps:').map((s) => s.trim());
    const project: ProjectBrowserSelection = { name, use: {} };
    if (value) {
      const [key, browser] = value.split(':').map((s) => s.trim());
      project.use = { [key]: browser };
    }
    if (depsSpec) {
      project.dependencies = depsSpec.split('+').map((s) => s.trim());
    }
    return project;
  });
}

const PROJECTS = 'browserPrerequisiteProjects';
const ARGV_SELECTION = 'browserPrerequisiteArgvSelection';

Given('宣言されている全プロジェクトのブラウザ指定が {string} である', async ({ ctx }, spec: string) => {
  const projects = parseProjectSelection(spec);
  ctx[BROWSERS] = requiredBrowserNames(resolveExecutedProjects(projects, null));
});

Given('宣言されているプロジェクトが {string} である', async ({ ctx }, spec: string) => {
  ctx[PROJECTS] = parseProjectSelection(spec);
});

Given('選択されたプロジェクトが {string} である', async ({ ctx }, spec: string) => {
  const selected = spec.split(',').map((s) => s.trim());
  const projects = ctx[PROJECTS] as ProjectBrowserSelection[];
  ctx[BROWSERS] = requiredBrowserNames(resolveExecutedProjects(projects, selected));
});

Given('Playwright に渡されたコマンドライン引数が {string} である', async ({ ctx }, argvSpec: string) => {
  const argv = argvSpec.trim() === '' ? [] : argvSpec.split(/\s+/);
  ctx[ARGV_SELECTION] = parseProjectSelectionFromArgv(argv);
});

Then('プロジェクトの選択は {string} である', async ({ ctx }, expected: string) => {
  const selection = ctx[ARGV_SELECTION] as string[] | null;
  if (expected === '指定なし') {
    expect(selection).toBeNull();
    return;
  }
  expect(selection).toEqual(expected.split(',').map((s) => s.trim()));
});

Then('前提確認が起動を試すブラウザは {string} である', async ({ ctx }, expected: string) => {
  expect((ctx[BROWSERS] as string[]).join(', ')).toBe(expected);
});

When('このホストで chromium の起動を実際に試す', async ({ ctx }) => {
  try {
    await checkBrowsersLaunchable(['chromium']);
    ctx[REAL_LAUNCH] = null;
  } catch (error) {
    ctx[REAL_LAUNCH] = error;
  }
});

Then('起動できたか、さもなくば導入コマンド付きの前提エラーとして報告される', async ({ ctx }) => {
  const error = ctx[REAL_LAUNCH] as Error | null;
  if (error === null) return;
  // 生の Playwright エラーをそのまま投げないこと。これが AC1 の実体である。
  expect(error).toBeInstanceOf(Error);
  expect(error.message).toContain(PREREQUISITE_ERROR_PREFIX);
  expect(error.message).toMatch(/npm run playwright:install|sudo npx playwright install-deps/);
});
