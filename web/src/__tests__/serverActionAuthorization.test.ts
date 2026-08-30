/**
 * @jest-environment node
 */
import fs from 'fs';
import path from 'path';

/**
 * すべての Server Action が認可について明示的であることを検証する(issue #824)。
 *
 * <p>#824 で判明したのは「Server Action 7個に認可チェックが無い」ことだが、根本は
 * **Server Action は認可を書き忘れても動いてしまう**ことにある。`"use server"` を付けた
 * 関数はブラウザから直接 POST できるエンドポイントになるが、そこに何も書かなければ
 * 誰でも実行できる状態で成立してしまい、型検査もリントも警告しない。
 *
 * <p>実際 #824 では `setupAction` のように**意図的に未認証であるべきもの**と、
 * `registerSiteAction` のような**付け忘れ**が混在しており、区別が記録されていなかった。
 *
 * <p>そこで「認可呼び出しがある」か「なぜ不要かがコメントで明示されている」かの
 * どちらかを必須にする。次に Server Action を足したとき、どちらも無ければここで落ちる。
 *
 * <p>同じ手法の先例: `services/gateway` の `RouteControllerContractTest` と
 * `DownstreamHealthConfigContractTest`(#743)。
 */

/** 認可を要求していると認める呼び出し。 */
const AUTH_CALLS = ['requireAdminSession(', 'requireSession(', 'requireSelfOrAdmin('];

/**
 * 認可が不要であることを明示するマーカー。
 * Server Action の直前の JSDoc に書く(理由も併記すること)。
 */
const EXEMPTION_MARKER = '意図的に未認証';

function findActionFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === '__tests__') continue;
      out.push(...findActionFiles(full));
    } else if (entry.name === 'actions.ts') {
      out.push(full);
    }
  }
  return out;
}

interface ServerAction {
  file: string;
  name: string;
  /** 関数本体(次の export まで)。 */
  body: string;
  /** 関数宣言の直前のテキスト(JSDoc を含む)。 */
  preamble: string;
}

function extractServerActions(file: string): ServerAction[] {
  const source = fs.readFileSync(file, 'utf-8');
  // "use server" がファイル先頭にあるものだけが Server Action のファイル。
  if (!/^\s*["']use server["']/m.test(source)) return [];

  const actions: ServerAction[] = [];
  const pattern = /export async function (\w+)\s*\(/g;
  const matches = [...source.matchAll(pattern)];

  matches.forEach((m, i) => {
    const start = m.index!;
    const end = i + 1 < matches.length ? matches[i + 1].index! : source.length;
    // 直前1500文字から JSDoc を拾う(手前の関数本体を含んでも判定には影響しない範囲で十分)。
    const preambleStart = i === 0 ? 0 : matches[i - 1].index!;
    actions.push({
      file,
      name: m[1],
      body: source.slice(start, end),
      preamble: source.slice(preambleStart, start),
    });
  });
  return actions;
}

describe('Server Action の認可(issue #824)', () => {
  const appDir = path.join(process.cwd(), 'src', 'app');
  const files = findActionFiles(appDir);
  const actions = files.flatMap(extractServerActions);

  it('actions.ts と Server Action を検出できている', () => {
    // 検出ロジックが壊れて「0件だから全部通る」という偽の成功にならないようにする。
    expect(files.length).toBeGreaterThan(5);
    expect(actions.length).toBeGreaterThan(20);
  });

  it('すべての Server Action が認可呼び出しを持つか、不要な理由を明示している', () => {
    const violations = actions
      .filter((action) => {
        const hasAuth = AUTH_CALLS.some((call) => action.body.includes(call));
        const isExempt = action.preamble.includes(EXEMPTION_MARKER);
        return !hasAuth && !isExempt;
      })
      .map((a) => `${path.relative(process.cwd(), a.file)}: ${a.name}`);

    expect(violations).toEqual([]);
  });
});
