import { chromium, firefox, webkit, type BrowserType } from '@playwright/test';

/**
 * Playwright のブラウザが**実際に起動できる**ことの前提確認(issue #1045)。
 *
 * ## なぜ必要か
 *
 * ブラウザが起動できないホストでは、Playwright は1本目のシナリオの中で落ち、残りは
 * 「did not run」になる。#1045 の実測は `1 failed / 72 did not run` で、原因は
 * 60 行のブラウザ起動ログの中に1行だけ埋もれていた。
 * `global-setup.ts` が docker のhealthy待ちと公開URLへの疎通を確認しているのと同じ理由で、
 * これも「原因不明の大量失敗」ではなく明確な前提エラーとして先に落とす。
 *
 * ## 症状は2種類あり、対処が違う
 *
 * | 症状 | 何が無いか | 対処 | root 権限 |
 * | --- | --- | --- | --- |
 * | `Executable doesn't exist at ...` | ブラウザ本体 | {@link PLAYWRIGHT_INSTALL_COMMAND} | 不要 |
 * | `error while loading shared libraries: libatk-1.0.so.0` | OS の共有ライブラリ | {@link PLAYWRIGHT_INSTALL_DEPS_COMMAND} | **必要** |
 *
 * この2つを混同すると、`playwright install` を何度打っても直らない状態に嵌る。
 * #1045 のコメント群がまさにそれで、「ブラウザが無い」と読んだ担当者が
 * `playwright install` を打ち、バイナリは在るのに起動しない状態のまま堂々巡りした。
 *
 * ## 導入方針(#1045 AC4)
 *
 * 判断とその理由は docs/e2e-testing.md §3.3 に書いてある。実装側の要点だけ再掲する:
 *
 * - **ブラウザのバージョンは別途固定しない。** revision を決めるのは
 *   `apps/web/package.json` の `@playwright/test` であり、`playwright install` は
 *   その版が要求する revision をそのまま取りに行く。別に固定すると正が2つになり、
 *   `@playwright/test` を上げた瞬間に静かにずれる。
 * - **root を要する導入は自動実行しない**(#1045 Requirement 4)。
 *   `playwright install --with-deps` は apt を root で走らせるため使わない。
 *   コマンドを**文面で示すだけ**にして、実行の判断は利用者に委ねる。
 */

/** ブラウザ本体を入れる唯一の入口。root 権限は要らない。 */
export const PLAYWRIGHT_INSTALL_COMMAND = 'cd apps/web && npm run playwright:install';

/**
 * OS の共有ライブラリを入れるコマンド。**root 権限が要るので自動実行しない。**
 *
 * 生の apt ではなくこちらを第一手にするのは、必要なパッケージ名が
 * ブラウザの版とディストリビューションの版の両方で変わるからである
 * (Ubuntu 24.04 以降は `libasound2` が `libasound2t64` に改名された、等)。
 * playwright-core がその対応表を持っており、`@playwright/test` を上げれば一緒に更新される。
 */
export const PLAYWRIGHT_INSTALL_DEPS_COMMAND = 'cd apps/web && sudo npx playwright install-deps';

/**
 * `install-deps` が対応していないディストリビューション向けの逃げ道(chromium 用)。
 *
 * playwright-core 1.62 の `ubuntu26.04-x64` 定義から書き写した、この開発ホスト
 * (Ubuntu 26.04)で実際に要るパッケージ。`t64` 接尾辞は Ubuntu 24.04 で行われた
 * 64bit time_t 移行によるもので、22.04 以前では接尾辞の無い名前になる。
 * docs/e2e-testing.md §3.3 の一覧がこれと一致することを
 * scripts/test_playwright_browser_setup.py が検査する。
 */
export const CHROMIUM_APT_PACKAGES = [
  'libasound2t64',
  'libatk-bridge2.0-0t64',
  'libatk1.0-0t64',
  'libatspi2.0-0t64',
  'libcairo2',
  'libcups2t64',
  'libdbus-1-3',
  'libdrm2',
  'libgbm1',
  'libglib2.0-0t64',
  'libnspr4',
  'libnss3',
  'libpango-1.0-0',
  'libx11-6',
  'libxcb1',
  'libxcomposite1',
  'libxdamage1',
  'libxext6',
  'libxfixes3',
  'libxkbcommon0',
  'libxrandr2',
];

/**
 * 前提エラーの目印。生の Playwright エラーと区別できるよう、必ずこの文字列で始める。
 * ステップ定義(browserPrerequisite.steps.ts)もこれで判定する。
 */
export const PREREQUISITE_ERROR_PREFIX = '[e2e] 前提エラー: Playwright のブラウザを起動できません';

/** 起動確認に使えるブラウザ。`playwright.config.ts` が宣言するのはこの3つ。 */
const BROWSER_TYPES: Record<string, BrowserType | undefined> = { chromium, firefox, webkit };

/** ブラウザを明示しないプロジェクトの既定。Playwright 自身の既定と同じ。 */
const DEFAULT_BROWSER = 'chromium';

/** `Executable doesn't exist at <path>` — ブラウザ本体が未導入。 */
const MISSING_EXECUTABLE_RE = /Executable doesn't exist/;

/** `error while loading shared libraries: libatk-1.0.so.0: cannot open ...` — OS 側が足りない。 */
const MISSING_SHARED_LIBRARY_RE = /error while loading shared libraries:\s*([^\s:]+)/;

export type BrowserLaunchDiagnosis =
  | { kind: 'missing-executable' }
  | { kind: 'missing-shared-library'; library: string }
  | { kind: 'unknown' };

/** `playwright.config.ts` のプロジェクトのうち、ブラウザの決定に関わる部分だけを見た形。 */
export type ProjectBrowserSelection = {
  name?: string;
  use?: { browserName?: string; defaultBrowserType?: string };
  /** `playwright.config.ts` の同名フィールド。依存先プロジェクトの名前の配列。 */
  dependencies?: string[];
};

/** 失敗メッセージから原因を判定する。判定は文字列だけを見るので、単体で検証できる。 */
export function diagnoseBrowserLaunchError(error: unknown): BrowserLaunchDiagnosis {
  const message = error instanceof Error ? error.message : String(error);
  // 本体が無ければ共有ライブラリの話は出ようがないので、本体の不在を先に見る。
  if (MISSING_EXECUTABLE_RE.test(message)) {
    return { kind: 'missing-executable' };
  }
  const library = MISSING_SHARED_LIBRARY_RE.exec(message);
  if (library !== null) {
    return { kind: 'missing-shared-library', library: library[1] };
  }
  return { kind: 'unknown' };
}

/**
 * 起動失敗を、原因と対処コマンドを添えた前提エラーの文面に変換する。
 *
 * 原因ごとに**その原因の対処だけ**を書く。両方を並べると、ブラウザ本体が無いだけの人に
 * 不要な `sudo` を打たせ、共有ライブラリが無い人に効かない `playwright install` を
 * 打たせることになる。#1045 で実際に起きた堂々巡りがそれである。
 */
export function describeBrowserLaunchFailure(browserName: string, error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error);
  const head = `${PREREQUISITE_ERROR_PREFIX}(${browserName})。`;
  const diagnosis = diagnoseBrowserLaunchError(error);

  if (diagnosis.kind === 'missing-executable') {
    return [
      head,
      '',
      '原因: ブラウザ本体が ~/.cache/ms-playwright に導入されていません。',
      '',
      '対処:',
      `    ${PLAYWRIGHT_INSTALL_COMMAND}`,
      '',
      '詳細: docs/e2e-testing.md §3.3',
    ].join('\n');
  }

  if (diagnosis.kind === 'missing-shared-library') {
    return [
      head,
      '',
      `原因: OS の共有ライブラリ ${diagnosis.library} がありません。`,
      '      ブラウザ本体は導入済みです。導入し直しても直りません。',
      '',
      '対処(root 権限が要るため自動実行していません。手で実行してください):',
      `    ${PLAYWRIGHT_INSTALL_DEPS_COMMAND}`,
      '',
      '  install-deps が対応していないディストリビューションでは apt で直接入れる:',
      `    sudo apt-get install -y ${CHROMIUM_APT_PACKAGES.join(' ')}`,
      '',
      '詳細: docs/e2e-testing.md §3.3',
    ].join('\n');
  }

  return [
    head,
    '',
    '原因: 判別できませんでした。Playwright が報告した内容をそのまま示します。',
    '',
    raw,
    '',
    '詳細: docs/e2e-testing.md §3.3',
  ].join('\n');
}

/**
 * 渡されたプロジェクトが必要とするブラウザを、重複を除いて列挙する。
 *
 * ブラウザ名を `chromium` に決め打ちしないのは、`playwright.config.ts` が
 * `CROSS_BROWSER_SPECS` / `at-cross-browser-*` 用に firefox / webkit のプロジェクトも
 * 宣言しているため。確認するブラウザは設定から導き、プロジェクトが増減したら自動で追随させる。
 *
 * <p>ここに渡す `projects` は**呼び出し側が絞り込んだ後の一覧**を渡すこと。
 * `globalSetup` が受け取る `FullConfig['projects']` 自体は Playwright が返す時点で
 * 既に**設定が宣言した全プロジェクト**であり、これは Playwright 自身の仕様で
 * 変わらない(`--project` による絞り込みは反映されない)。#1194 より前はこれを理由に
 * 絞り込みを諦め、常に全プロジェクトのブラウザを確認していたため、
 * `npm run test:at:fast`(`--project=at-main`。chromium しか使わない)でも
 * firefox / webkit の起動可否を要求し、OS の共有ライブラリが片方でも欠けると
 * chromium だけで足りる実行まで一律に落ちていた。
 * #1194 以降は `globalSetup` 側で {@link resolveExecutedProjects} を使い、CLI の
 * `--project` 引数({@link parseProjectSelectionFromArgv})から実際に実行される
 * プロジェクト(選択したプロジェクトとその依存先)だけに絞ってからここへ渡す。
 * `--project` が指定されない実行(全プロジェクトを回す)では、これまで通り
 * 全プロジェクトを渡せばよい。
 */
export function requiredBrowserNames(projects: readonly ProjectBrowserSelection[]): string[] {
  const names = projects.map(
    (project) => project.use?.browserName ?? project.use?.defaultBrowserType ?? DEFAULT_BROWSER
  );
  return [...new Set(names)];
}

/**
 * `playwright.config.ts` の `dependencies` を辿り、実際に実行されるプロジェクトを解決する(#1194)。
 *
 * `selectedNames` が `null` のときは「`--project` 未指定 = 全プロジェクトを実行する」を
 * 意味し、`projects` をそのまま返す。`--project` が指定されている場合は、選択された
 * プロジェクトと、その `dependencies` が指すプロジェクトを推移的に(依存の依存も)辿って
 * 集める。Playwright は `--project` を指定すると、選択したプロジェクトが依存する
 * プロジェクトを自動で先に実行するため、ここで解決する集合は実際に起動されるものと一致する。
 */
export function resolveExecutedProjects(
  projects: readonly ProjectBrowserSelection[],
  selectedNames: readonly string[] | null
): ProjectBrowserSelection[] {
  if (selectedNames === null) return [...projects];

  const byName = new Map(
    projects.filter((project) => project.name !== undefined).map((project) => [project.name as string, project])
  );
  const included = new Set<string>();
  const pending = [...selectedNames];
  while (pending.length > 0) {
    const name = pending.pop() as string;
    if (included.has(name)) continue;
    included.add(name);
    const project = byName.get(name);
    for (const dependency of project?.dependencies ?? []) {
      pending.push(dependency);
    }
  }

  return projects.filter((project) => project.name !== undefined && included.has(project.name));
}

/** `--project` の値の1つ分。`--project=X` / `--project X` の両方をここに正規化する。 */
const PROJECT_FLAG_RE = /^--project(?:=(.*))?$/;

/**
 * Playwright の CLI 引数(`process.argv` 相当)から `--project` の選択を取り出す(#1194)。
 *
 * `--project` は Playwright 自身が複数回の指定をサポートする(例:
 * `--project=at-cross-browser-firefox --project=at-cross-browser-webkit`)ため、
 * ここでも全ての出現を集める。1つも無ければ「絞り込み無し(= 全プロジェクト実行)」を
 * 表す `null` を返す。`resolveExecutedProjects` の `selectedNames` はこの区別を前提にしている。
 */
export function parseProjectSelectionFromArgv(argv: readonly string[]): string[] | null {
  const selected: string[] = [];
  for (let i = 0; i < argv.length; i++) {
    const match = PROJECT_FLAG_RE.exec(argv[i]);
    if (match === null) continue;
    if (match[1] !== undefined) {
      // `--project=X` 形式。
      selected.push(match[1]);
      continue;
    }
    // `--project X` 形式。値は次のトークン。
    const value = argv[i + 1];
    if (value !== undefined) {
      selected.push(value);
      i++;
    }
  }
  return selected.length > 0 ? selected : null;
}

/**
 * 指定されたブラウザを1つずつ実際に起動して、すぐ閉じる。
 *
 * 「バイナリが在るか」をファイルの存在で確かめないのは、#1045 のホストがまさに
 * **バイナリは在るのに起動できない**状態だったから。起動してみる以外に確かめようがない。
 */
export async function checkBrowsersLaunchable(browserNames: readonly string[]): Promise<void> {
  for (const name of browserNames) {
    const browserType = BROWSER_TYPES[name];
    // 知らない名前は Playwright 自身が報告する。ここで独自のエラーを被せない。
    if (browserType === undefined) continue;

    let browser;
    try {
      browser = await browserType.launch();
    } catch (error) {
      throw new Error(describeBrowserLaunchFailure(name, error));
    }
    await browser.close();
  }
}
