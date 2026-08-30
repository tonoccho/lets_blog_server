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

/**
 * 認可を要求していると認める呼び出し。
 *
 * 注意: このテストが守れるのは**有無だけ**で、水準(admin か session か)の格下げは検出できない。
 * `requireAdminSession()` を `requireSession()` に変えてもここは通る。
 */
const AUTH_CALLS = ['requireAdminSession(', 'requireSession('];

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
  /** 関数本体(次の Server Action の宣言まで)。コメント除去済み。 */
  body: string;
  /** 関数宣言の直前のテキスト。JSDoc を残すため**コメント除去前**のソースから取る。 */
  preamble: string;
}

/**
 * コメントを空白に置き換える(位置をずらさないため長さは保つ)。
 *
 * 本文の判定をコメント除去後に行うのは、次の2つの偽陰性を潰すため。
 *
 * 1. **後続関数の JSDoc が前の関数の本体に入る**。本体を「次の宣言まで」で切るため、
 *    JSDoc に `requireSession()` と書いてあるだけで手前のアクションが認可済みと判定されていた。
 *    実際 `setup/actions.ts` の JSDoc にはこの文字列が含まれており、その上にアクションを
 *    1つ足すと無言で素通りする状態だった。
 * 2. **コメントアウトされた認可呼び出し**が認可ありと判定される。
 *    デバッグで一時的に外して戻し忘れる、というこのテストが防ぐべき失敗そのもの。
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => ' '.repeat(m.length))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length));
}

/**
 * Server Action の宣言を拾う。
 *
 * `export async function foo(` に加えて `export const foo = async (` 形式も対象にする。
 * 後者は Next.js の Server Action として完全に有効な書き方で、#824 の当初の実装では
 * **1件も検出できていなかった**(現状のリポジトリには存在しないが、次に誰かが
 * その形式で書いた瞬間に無言で素通りする)。
 */
const ACTION_DECLARATION =
  /export\s+(?:async\s+function\s+(\w+)|const\s+(\w+)\s*(?::[^=]+)?=\s*async\s*[(<])/g;

function extractServerActions(file: string): ServerAction[] {
  const source = fs.readFileSync(file, 'utf-8');
  // "use server" がファイル先頭にあるものだけが Server Action のファイル。
  if (!/^\s*["']use server["']/m.test(source)) return [];

  const stripped = stripComments(source);
  const actions: ServerAction[] = [];
  const matches = [...stripped.matchAll(ACTION_DECLARATION)];

  matches.forEach((m, i) => {
    const start = m.index!;
    const end = i + 1 < matches.length ? matches[i + 1].index! : source.length;
    // preamble は「前の宣言の直後」から。前の関数の本体を含めないため、
    // 本体内に免除マーカーが書かれていても次の関数が免除されない。
    // 先頭の関数だけは import 部分を含めないよう "use server" の直後から取る。
    const previousEnd = i === 0 ? 0 : matches[i - 1].index! + matches[i - 1][0].length;
    actions.push({
      file,
      name: m[1] ?? m[2],
      // 本体はコメント除去後で判定する
      body: stripped.slice(start, end),
      // 免除マーカーは JSDoc に書くので、こちらはコメントを残したソースから取る
      preamble: source.slice(previousEnd, start),
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
    // 実測(2026-08-31)は 18ファイル / 102アクション。抽出が大きく壊れたら気付けるよう、
    // 実測に近い下限を置く(アクションを消したときは下限も一緒に下げること)。
    expect(files.length).toBeGreaterThanOrEqual(15);
    expect(actions.length).toBeGreaterThanOrEqual(90);
  });

  /**
   * 検出ロジック自体の回帰テスト(#824 のレビューで実証された偽陰性を固定する)。
   *
   * 一時ファイルを作らずに済むよう、判定に使う純粋な部分だけを再現して検証する。
   * ここが緩むと「認可が無いのに通る」状態に戻るため、実際のソース走査とは別に押さえておく。
   */
  describe('検出ロジックの回帰(偽陰性の固定)', () => {
    const hasAuth = (body: string) => AUTH_CALLS.some((call) => stripComments(body).includes(call));

    it('コメントアウトされた認可呼び出しは認可ありと見なさない', () => {
      expect(hasAuth('{ // await requireSession();\n return doThing(id); }')).toBe(false);
      expect(hasAuth('{ /* await requireAdminSession(); */ return x(); }')).toBe(false);
    });

    it('実際の認可呼び出しは認可ありと見なす', () => {
      expect(hasAuth('{ await requireSession();\n return doThing(id); }')).toBe(true);
    });

    it('アロー関数形式の Server Action を検出できる', () => {
      const declarations = [
        ...'export const evilB = async (id) => {}'.matchAll(ACTION_DECLARATION),
      ];
      expect(declarations.map((m) => m[1] ?? m[2])).toEqual(['evilB']);
    });

    it('型注釈付きのアロー関数形式も検出できる', () => {
      const declarations = [
        ...'export const foo: Handler = async (id: number) => {}'.matchAll(ACTION_DECLARATION),
      ];
      expect(declarations.map((m) => m[1] ?? m[2])).toEqual(['foo']);
    });

    it('従来の関数宣言形式も引き続き検出できる', () => {
      const declarations = [
        ...'export async function bar(id: number) {}'.matchAll(ACTION_DECLARATION),
      ];
      expect(declarations.map((m) => m[1] ?? m[2])).toEqual(['bar']);
    });
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
