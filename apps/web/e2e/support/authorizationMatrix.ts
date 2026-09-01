/**
 * `docs/AUTHORIZATION_MATRIX.md` を**実行時に**読み込む(issue #943 / AT-17)。
 *
 * AT-17 の受け入れ基準は「シナリオ1・2 が認可表を実際に読み込んでおり、表と実装がずれたら
 * 落ちる(表を手で書き写さない)」である。ここに表の内容をハードコードしたら、その基準は
 * 満たされない。パースの失敗は**沈黙させず例外にする**(行が0件なら表の形式が変わっている)。
 *
 * ## 表の形
 *
 * ```
 * ## XxxController (N エンドポイント、ベースパス `/api/xxx`)
 *
 * | HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
 * | POST .../chat | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
 * ```
 *
 * - 先頭セルの `...` は直前の見出しのベースパスを指す
 * - 「認証ゲートの実施レイヤー」節の identity 用の表のように、見出しを持たず
 *   `POST・DELETE /api/users/{userId}/roles/{roleName}` や
 *   `GET /api/users/{id}・PUT /api/users/{id}・PATCH /{id}/preferences` と
 *   1セルへ複数を書いた行もある。これらも取りこぼさずに読む。
 */
import fs from 'node:fs';
import path from 'node:path';

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
export const AUTHORIZATION_MATRIX_PATH = 'docs/AUTHORIZATION_MATRIX.md';

export interface AuthorizationMatrixRow {
  /** 由来のコントローラ見出し(見出しの無い表では空文字)。 */
  controller: string;
  method: string;
  path: string;
  /** 「認可チェック」列。 */
  authorization: string;
  /** 「未認証」列。`401` か「該当なし(公開エンドポイント)」。 */
  unauthenticated: string;
  /** 「権限不足」列。`403` か「該当なし」。 */
  insufficient: string;
}

const HTTP_METHODS = 'GET|POST|PUT|DELETE|PATCH';
const CONTROLLER_HEADING = new RegExp(
  `^##\\s+(\\w+Controller)\\s*\\(\\d+エンドポイント、(?:ベースパスなし|ベースパス\\s*\`([^\`]+)\`)\\)`
);
/** 1セル内の「メソッド(・メソッド)* パス」または「・に続くパスだけ」を順に拾う。 */
const PATH_TOKEN = new RegExp(
  `((?:${HTTP_METHODS})(?:・(?:${HTTP_METHODS}))*)?\\s*(\\.\\.\\.[\\w{}/.-]*|/[\\w{}/*.-]+)`,
  'g'
);

export function readAuthorizationMatrix(): AuthorizationMatrixRow[] {
  const file = path.join(REPO_ROOT, AUTHORIZATION_MATRIX_PATH);
  const lines = fs.readFileSync(file, 'utf8').split('\n');

  const rows: AuthorizationMatrixRow[] = [];
  let controller = '';
  let basePath = '';

  for (const line of lines) {
    const heading = CONTROLLER_HEADING.exec(line);
    if (heading) {
      controller = heading[1];
      basePath = heading[2] ?? '';
      continue;
    }
    if (line.startsWith('## ')) {
      controller = '';
      basePath = '';
      continue;
    }
    if (!line.startsWith('|')) {
      continue;
    }
    const cells = line.split('|').slice(1, -1).map((cell) => cell.trim().replace(/`/g, ''));
    if (cells.length < 2 || !new RegExp(`^(?:${HTTP_METHODS})\\b`).test(cells[0])) {
      continue;
    }
    rows.push(...parseFirstCell(cells, controller, basePath));
  }

  if (rows.length === 0) {
    throw new Error(
      `${AUTHORIZATION_MATRIX_PATH} から認可の行を1件も読み取れませんでした。`
        + '表の形式が変わった可能性があります(このファイルのJSDocを参照)。'
    );
  }
  return rows;
}

function parseFirstCell(
  cells: string[], controller: string, basePath: string
): AuthorizationMatrixRow[] {
  const rows: AuthorizationMatrixRow[] = [];
  let methods: string[] = [];
  /**
   * 相対パス(`/{id}/preferences` のように `/api` で始まらないもの)を解決するための基準。
   * セル内で**最初に現れた絶対パス**から導く。`/api/users/{id}` なら `/api/users`、
   * `/api/identity/me` なら `/api/identity`(最初のパス変数の手前、無ければ末尾セグメントを落とす)。
   */
  let relativeBase: string | null = null;

  PATH_TOKEN.lastIndex = 0;
  let token = PATH_TOKEN.exec(cells[0]);
  while (token !== null) {
    if (token[1]) {
      methods = token[1].split('・');
    }
    let endpointPath = token[2];
    if (endpointPath.startsWith('...')) {
      endpointPath = basePath + endpointPath.slice(3);
    } else if (!endpointPath.startsWith('/api')) {
      if (relativeBase === null) {
        token = PATH_TOKEN.exec(cells[0]);
        continue;
      }
      endpointPath = relativeBase + endpointPath;
    } else if (relativeBase === null) {
      relativeBase = parentOf(endpointPath);
    }
    for (const method of methods) {
      rows.push({
        controller,
        method,
        path: endpointPath,
        authorization: cells[1] ?? '',
        unauthenticated: cells[2] ?? '',
        insufficient: cells[3] ?? '',
      });
    }
    token = PATH_TOKEN.exec(cells[0]);
  }
  return rows;
}

function parentOf(endpointPath: string): string {
  const variableAt = endpointPath.indexOf('{');
  if (variableAt >= 0) {
    return endpointPath.slice(0, variableAt).replace(/\/$/, '');
  }
  return endpointPath.slice(0, endpointPath.lastIndexOf('/'));
}
