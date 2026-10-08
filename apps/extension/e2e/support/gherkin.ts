/**
 * 日本語 Gherkin(`.feature`)を jest のテストへ変換する最小のランナー(issue #942 / AT-16)。
 *
 * apps/web は playwright-bdd を使うが(docs/ACCEPTANCE_TESTING.md §2)、拡張の受け入れテストは
 * ブラウザを一切使わず、拡張自身の apiClient / httpClient を Node 上で直接呼ぶ。Playwright を
 * 持ち込む理由が無いので、拡張が既に使っている jest(ts-jest)の上で `.feature` を実行する。
 *
 * 記法・タグの意味は apps/web と揃える(docs/ACCEPTANCE_TESTING.md §4/§6)。
 *   `# language: ja` / `機能:` / `背景:` / `シナリオ:` / `シナリオアウトライン:` / `例:`
 *   `前提` / `もし` / `ならば` / `かつ` / `しかし`
 *   `@api` `@slow` `@stub` `@destructive` + ドメインタグ
 */

import * as fs from 'fs';
import * as path from 'path';

/** シナリオ1件ぶんの共有状態。ステップ定義はこれを介してのみ値を受け渡す。 */
export interface World {
  [key: string]: unknown;
}

type StepFn = (world: World, ...args: string[]) => void | Promise<void>;

interface StepDefinition {
  pattern: RegExp;
  /** 定義元の文言(重複登録の検出とエラーメッセージのため)。 */
  text: string;
  fn: StepFn;
}

const definitions: StepDefinition[] = [];

/**
 * ステップ定義の文言を正規表現へ変換する。`{string}` は引用符付き文字列、`{int}` は整数を捕捉する
 * (cucumber expression の最小サブセット)。
 */
function toPattern(text: string): RegExp {
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const withParams = escaped
    .replace(/\\\{string\\\}/g, '"([^"]*)"')
    .replace(/\\\{int\\\}/g, '(-?\\d+)');
  return new RegExp(`^${withParams}$`);
}

function define(text: string, fn: StepFn): void {
  if (definitions.some((d) => d.text === text)) {
    throw new Error(`ステップ定義が重複しています: ${text}`);
  }
  definitions.push({ pattern: toPattern(text), text, fn });
}

/**
 * 前提/もし/ならば は playwright-bdd と同じく「同じ文言は1回しか定義できない」規約にする
 * (docs/ACCEPTANCE_TESTING.md §6 の原則2)。キーワードごとに名前空間を分けない。
 */
export const Given = define;
export const When = define;
export const Then = define;

interface Step {
  keyword: string;
  text: string;
  /** ドキュメンテーション文字列(""" で囲んだ複数行)。 */
  docString?: string;
}

interface Scenario {
  name: string;
  tags: string[];
  steps: Step[];
  line: number;
}

interface Feature {
  name: string;
  tags: string[];
  background: Step[];
  scenarios: Scenario[];
  file: string;
}

const STEP_KEYWORDS = ['前提', 'もし', 'ならば', 'かつ', 'しかし'];

/** `.feature` の本文を解析する。未知のキーワードは黙って捨てず例外にする。 */
export function parseFeature(source: string, file: string): Feature {
  const lines = source.split(/\r?\n/);
  const feature: Feature = { name: '', tags: [], background: [], scenarios: [], file };

  let pendingTags: string[] = [];
  let target: Step[] | undefined;
  let current: Scenario | undefined;
  let outline: { scenario: Scenario; headers?: string[] } | undefined;

  for (let i = 0; i < lines.length; i += 1) {
    const raw = lines[i];
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;

    if (line.startsWith('@')) {
      pendingTags = pendingTags.concat(line.split(/\s+/).filter((t) => t.startsWith('@')));
      continue;
    }

    if (line.startsWith('機能:')) {
      feature.name = line.slice('機能:'.length).trim();
      feature.tags = pendingTags;
      pendingTags = [];
      target = undefined;
      continue;
    }

    if (line.startsWith('背景:')) {
      target = feature.background;
      current = undefined;
      outline = undefined;
      continue;
    }

    if (line.startsWith('シナリオアウトライン:') || line.startsWith('シナリオ:')) {
      const isOutline = line.startsWith('シナリオアウトライン:');
      const name = line.slice(line.indexOf(':') + 1).trim();
      current = { name, tags: feature.tags.concat(pendingTags), steps: [], line: i + 1 };
      pendingTags = [];
      target = current.steps;
      outline = isOutline ? { scenario: current } : undefined;
      if (!isOutline) feature.scenarios.push(current);
      continue;
    }

    if (line.startsWith('例:')) {
      if (!outline) throw new Error(`${file}:${i + 1} 「例:」はシナリオアウトラインの中にのみ書けます`);
      target = undefined;
      continue;
    }

    if (line.startsWith('|')) {
      if (!outline) throw new Error(`${file}:${i + 1} 表はシナリオアウトラインの「例:」でのみ使えます`);
      const cells = line.split('|').slice(1, -1).map((c) => c.trim());
      if (!outline.headers) {
        outline.headers = cells;
      } else {
        feature.scenarios.push(expandOutline(outline.scenario, outline.headers, cells));
      }
      continue;
    }

    if (line.startsWith('"""')) {
      const collected: string[] = [];
      i += 1;
      while (i < lines.length && lines[i].trim() !== '"""') {
        collected.push(lines[i]);
        i += 1;
      }
      const steps = target;
      if (!steps || steps.length === 0) {
        throw new Error(`${file} ドキュメンテーション文字列に対応するステップがありません`);
      }
      steps[steps.length - 1].docString = dedent(collected);
      continue;
    }

    const keyword = STEP_KEYWORDS.find((k) => line.startsWith(k));
    if (!keyword) {
      throw new Error(`${file}:${i + 1} 解釈できない行です: ${line}`);
    }
    if (!target) {
      throw new Error(`${file}:${i + 1} 「背景:」または「シナリオ:」の外にステップが書かれています`);
    }
    target.push({ keyword, text: line.slice(keyword.length).trim() });
  }

  if (!feature.name) throw new Error(`${file} に「機能:」がありません`);
  return feature;
}

/** `例:` の1行から具体シナリオを作る。`<列名>` を値へ置換する。 */
function expandOutline(template: Scenario, headers: string[], cells: string[]): Scenario {
  const substitute = (text: string): string =>
    headers.reduce((acc, header, index) => acc.split(`<${header}>`).join(cells[index] ?? ''), text);
  return {
    name: substitute(template.name),
    tags: template.tags,
    line: template.line,
    steps: template.steps.map((s) => ({
      keyword: s.keyword,
      text: substitute(s.text),
      docString: s.docString ? substitute(s.docString) : undefined,
    })),
  };
}

function dedent(lines: string[]): string {
  const indents = lines.filter((l) => l.trim() !== '').map((l) => l.length - l.trimStart().length);
  const min = indents.length > 0 ? Math.min(...indents) : 0;
  return lines.map((l) => l.slice(min)).join('\n');
}

/** 除外タグ(既定は無し)。`AT_EXCLUDE_TAGS=@slow,@destructive` のように指定する。 */
function excludedTags(): string[] {
  return (process.env.AT_EXCLUDE_TAGS ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter((t) => t !== '');
}

/** 対象タグ(既定は全件)。`AT_TAGS=@ai` のように指定すると、そのタグを持つシナリオだけを実行する。 */
function includedTags(): string[] {
  return (process.env.AT_TAGS ?? '')
    .split(',')
    .map((t) => t.trim())
    .filter((t) => t !== '');
}

function isSelected(tags: string[]): boolean {
  const include = includedTags();
  if (include.length > 0 && !include.some((t) => tags.includes(t))) return false;
  return !excludedTags().some((t) => tags.includes(t));
}

function findDefinition(step: Step): StepDefinition & { args: string[] } {
  for (const definition of definitions) {
    const match = definition.pattern.exec(step.text);
    if (match) return { ...definition, args: match.slice(1) };
  }
  throw new Error(`ステップ定義が見つかりません: ${step.keyword} ${step.text}`);
}

async function runStep(step: Step, world: World): Promise<void> {
  const definition = findDefinition(step);
  const args = step.docString !== undefined ? [...definition.args, step.docString] : definition.args;
  await definition.fn(world, ...args);
}

/** `.feature` を再帰的に集める。 */
export function collectFeatureFiles(dir: string): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((entry) => {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) return collectFeatureFiles(full);
      return entry.name.endsWith('.feature') ? [full] : [];
    })
    .sort();
}

/** シナリオ実行前に呼ばれるフック(タグを見て前提条件を確認する用途)。 */
export type BeforeScenarioHook = (tags: string[], world: World) => void | Promise<void>;

const beforeHooks: BeforeScenarioHook[] = [];
export function BeforeScenario(hook: BeforeScenarioHook): void {
  beforeHooks.push(hook);
}

/** シナリオの成否にかかわらず実行後に呼ばれるフック(シナリオが変えた共有環境を戻す用途)。 */
export type AfterScenarioHook = (tags: string[], world: World) => void | Promise<void>;

const afterHooks: AfterScenarioHook[] = [];
export function AfterScenario(hook: AfterScenarioHook): void {
  afterHooks.push(hook);
}

/**
 * 集めた `.feature` を jest の describe/test として登録する。
 * シナリオ名はレポートへそのまま出る(docs/ACCEPTANCE_TESTING.md §5)。
 */
export function runFeatures(featuresDir: string): void {
  for (const file of collectFeatureFiles(featuresDir)) {
    const feature = parseFeature(fs.readFileSync(file, 'utf-8'), file);
    const selected = feature.scenarios.filter((s) => isSelected(s.tags));
    if (selected.length === 0) continue;

    describe(feature.name, () => {
      for (const scenario of selected) {
        const title = `${scenario.name} ${scenario.tags.join(' ')}`.trim();
        test(title, async () => {
          const world: World = {};
          for (const hook of beforeHooks) {
            await hook(scenario.tags, world);
          }
          try {
            for (const step of [...feature.background, ...scenario.steps]) {
              try {
                await runStep(step, world);
              } catch (error) {
                const message = error instanceof Error ? error.message : String(error);
                throw new Error(`[${step.keyword} ${step.text}] ${message}`);
              }
            }
          } finally {
            for (const hook of afterHooks) {
              await hook(scenario.tags, world);
            }
          }
        });
      }
    });
  }
}
