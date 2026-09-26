/**
 * 実装(各サービスのコントローラーソース)から「gateway 経由で公開しているエンドポイント」を
 * 抽出する(issue #943 / AT-17)。
 *
 * ## なぜソースを走査するのか
 *
 * AT-17 の受け入れ基準は「**実装から抽出した**エンドポイント集合と認可表の差分が空である」
 * (シナリオ2)と「**全公開エンドポイント**が gateway 経由で到達する」(シナリオ4)である。
 * 手で書き写した一覧を突き合わせても、書き写しが古くなるだけで何も守れない。
 * OpenAPI 定義のような二次生成物も、生成が止まれば同じ問題を持つ。
 * 一次情報である `@RequestMapping` そのものを読む。
 *
 * ## JUnit 側との役割分担(issue #943 の Implementation Notes)
 *
 * `services/gateway/src/test/java/com/letsblog/gateway/config/RouteControllerContractTest.java`
 * が同じ走査を行い、**ルート表の転送先が正しいサービスか**を静的に検証する。そちらが正である。
 * ここは「利用者から見て到達するか / 認可表に載っているか」だけを見る。
 * 走査の見落としがあれば両方が同時に緩むが、それは走査規約(下記)を1か所に書いて
 * 双方が同じ規約に従うことで受け入れている。コンパイル時結合を作らないという
 * RouteControllerContractTest の判断は、TypeScript 側からはそもそも選択肢が無い。
 *
 * ## 走査規約(RouteControllerContractTest と同じ)
 *
 * - 対象は `services/<svc>/src/main/java/**\/*Controller.java`
 * - クラスレベルの `@RequestMapping("...")` は**クラス宣言行の直前の非空行**にある前提
 * - メソッドレベルの `@GetMapping` 等はパス文字列を持たなければクラスレベルのパスを使う
 * - `/api/internal/**` はサービス間の内部ブリッジで gateway を経由しないため除外
 */
import fs from 'node:fs';
import path from 'node:path';

/** リポジトリルート(apps/web/e2e/support から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 走査対象のサービス。gateway 自身は下流を持たないため含めない。 */
export const SERVICE_MODULES = [
  'identity', 'media', 'ai', 'content', 'analytics', 'project', 'log-writer', 'publishing', 'platform',
] as const;

export type ServiceModule = (typeof SERVICE_MODULES)[number];

export interface ControllerEndpoint {
  service: ServiceModule;
  controller: string;
  method: string;
  /** `@RequestMapping` に書かれたままのパス(`{id}` を含む)。 */
  path: string;
  /**
   * ハンドラが**必須の入力**(`@RequestBody`、または `required = false` でない
   * `@RequestParam`)を宣言しているか。
   *
   * Spring はこれらの解決に失敗した時点で400を返し、ハンドラ本体にある認可チェックまで
   * 到達しない。「認可が403として効いているか」を確かめる側は、入力を組み立てずに叩ける
   * エンドポイントかどうかをここで区別する(issue #943 / AT-17)。
   */
  requiresInput: boolean;
}

/**
 * `/api/internal/**` の命名規則に従わないが、gateway のルート表に**意図的に載せていない**
 * エンドポイント。RouteControllerContractTest の `NON_GATEWAY_ROUTED_PATHS` と同じ集合で、
 * 同じ理由(コンテナ間で直接呼ばれる経路しか無い)による。
 * 公開エンドポイントではないので、到達性も認可表への掲載も要求しない。
 */
export const NON_GATEWAY_ROUTED_PATHS = [
  '/api/comfyui/checkpoints/install',
  '/api/comfyui/checkpoints/delete',
  '/api/render/plantuml',
  '/api/render/recharts',
  '/api/render/penpot/design-file',
];

/**
 * gateway の `upload-endpoint` バケットに入るパス
 * (`services/gateway/src/main/java/com/letsblog/gateway/config/RateLimitWebFilter.java`)。
 *
 * このバケットは**プロセス全体で1時間に10回**しかない。全エンドポイントを1回ずつ叩く
 * シナリオがここを踏むと、わずかな本数のために枠を使い切り、同じ1時間に走る画像アップロード系の
 * シナリオを巻き添えで429にする。したがって一斉走査の対象からは外す
 * (routing の担保は RouteControllerContractTest 側にある。docs/ACCEPTANCE_TESTING.md §11)。
 *
 * issue #999より前は「`/upload`か`/image`を含むパスはupload-endpoint、ただし例外リストに
 * 載っている3件は除く」という**ブロックリスト**方式だった。`/image`を含む新しいパス
 * (`GET /api/projects/{id}/image-settings`等)が増えるたびに、例外リストへ追加し忘れて
 * 巻き込まれる事故を繰り返した(issue #999)。
 *
 * 今は逆に、実アップロード・実生成という「重い操作」だけを明示的に列挙する**許可リスト**
 * 方式にしている。新しい軽量な画像関連メタデータAPIが増えても、ここに追加しない限りは
 * 自動的にapi-global(通常の枠)に入るため、同種の事故が起きない。
 *
 * gateway側(`RateLimitWebFilter`)と全く同じ定義をこちらにも持つ(二重管理)。
 * 両者が食い違っていないことは
 * `services/gateway/src/test/java/com/letsblog/gateway/config/RateLimitUploadBucketSyncTest.java`
 * が検証している(issue #999 受入基準4)。
 */
const UPLOAD_BUCKET_EXACT_PATHS = [
  // 実際の画像/メディアバイナリのアップロード。
  '/api/media/upload',
  // 実際の画像生成(ComfyUI/ChatGPT呼び出し)。`/api/ai/image-options`(設定の参照)を
  // 巻き込まないよう、部分一致ではなく完全一致で扱う。
  '/api/ai/image',
  // `/api/ai/image/jobs`(画像生成の非同期受理口、issue #1405)は意図的に含めない。受理は
  // ジョブ1件の作成で終わり、GPU占有は専用Executorが直列化するため、共有枠ではなく
  // api-globalに置く(gateway `RateLimitWebFilter#UPLOAD_BUCKET_EXACT_PATHS` のJavadoc参照)。
];

/** `POST /api/projects/{id}/asset-images/{generatedImageId}/upload`: 生成済み画像を各環境へ実アップロードする。 */
const ASSET_IMAGE_UPLOAD_PATTERN = new RegExp('^/api/projects/[^/]+/asset-images/[^/]+/upload$');

/**
 * `POST /api/projects/{id}/bulk-management/upload`: プラグイン/テーマ/CSV等のファイルを
 * 各環境へ実際にmultipartアップロードし、一括適用する(`BulkManagementController`)。
 * 画像アップロードではないが実際の重いファイルアップロードであるため、upload-endpointに残す
 * (issue #999の実装判断)。
 */
const BULK_MANAGEMENT_UPLOAD_PATTERN = new RegExp('^/api/projects/[^/]+/bulk-management/upload$');

export function isUploadBucketPath(requestPath: string): boolean {
  return UPLOAD_BUCKET_EXACT_PATHS.includes(requestPath)
    || ASSET_IMAGE_UPLOAD_PATTERN.test(requestPath)
    || BULK_MANAGEMENT_UPLOAD_PATTERN.test(requestPath);
}

/** `{id}` のようなパス変数を具体値へ置き換える(存在しないIDを狙う)。 */
export function toSamplePath(pattern: string): string {
  return pattern.replace(/\{[^/}]+\}/g, '1');
}

/** パス変数の名前の違いを無視して比較できる形にする(`{id}` も `{projectId}` も `*`)。 */
export function toComparablePath(pattern: string): string {
  return pattern.replace(/\{[^/}]+\}/g, '*');
}

const MAPPING_ANNOTATION =
  /@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)")?/g;
const CLASS_DECLARATION = /^\s*(?:public\s+)?class\s+\w+Controller\b/;
const INTERNAL_PREFIX = '/api/internal/';

/** 実装から公開エンドポイントを抽出する。`/api/internal/**` は含まない。 */
export function scanPublicEndpoints(): ControllerEndpoint[] {
  const endpoints: ControllerEndpoint[] = [];
  for (const service of SERVICE_MODULES) {
    const root = path.join(REPO_ROOT, 'services', service, 'src', 'main', 'java');
    if (!fs.existsSync(root)) {
      throw new Error(`コントローラーのディレクトリが見つかりません: ${root}`);
    }
    for (const file of findControllerFiles(root)) {
      endpoints.push(...scanControllerFile(file, service));
    }
  }
  if (endpoints.length === 0) {
    throw new Error(
      'コントローラーの走査結果が空です。走査規約(このファイルのJSDoc)が実装のスタイルと'
        + '食い違っている可能性があります。'
    );
  }
  return endpoints;
}

function findControllerFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...findControllerFiles(full));
    } else if (entry.name.endsWith('Controller.java')) {
      found.push(full);
    }
  }
  return found;
}

function scanControllerFile(file: string, service: ServiceModule): ControllerEndpoint[] {
  const lines = fs.readFileSync(file, 'utf8').split('\n');
  const controller = path.basename(file, '.java');
  const classLevel = findClassLevelRequestMapping(lines);

  const endpoints: ControllerEndpoint[] = [];
  for (let index = 0; index < lines.length; index += 1) {
    if (index === classLevel?.lineIndex) {
      continue;
    }
    MAPPING_ANNOTATION.lastIndex = 0;
    let match = MAPPING_ANNOTATION.exec(lines[index]);
    while (match !== null) {
      const [, verb, rawPath] = match;
      // 値の無いアノテーションはクラスレベルのパスをそのまま使う。
      const relative = rawPath ?? (classLevel ? '' : null);
      if (relative !== null && verb !== 'Request') {
        const fullPath = joinPaths(classLevel?.path, relative);
        if (!fullPath.startsWith(INTERNAL_PREFIX)) {
          endpoints.push({
            service,
            controller,
            method: verb.toUpperCase(),
            path: fullPath,
            requiresInput: declaresRequiredInput(lines, index),
          });
        }
      }
      match = MAPPING_ANNOTATION.exec(lines[index]);
    }
  }
  return endpoints;
}

/**
 * アノテーション行に続くハンドラの引数リストを読み、必須の入力を宣言しているかを判定する。
 * 引数リストの終わり(`) {` を含む行)までを見る。
 */
function declaresRequiredInput(lines: string[], annotationLineIndex: number): boolean {
  for (let i = annotationLineIndex; i < Math.min(lines.length, annotationLineIndex + 30); i += 1) {
    const line = lines[i];
    if (line.includes('@RequestBody')) {
      return true;
    }
    if (line.includes('@RequestPart')) {
      return true;
    }
    if (
      line.includes('@RequestParam')
      && !line.includes('required = false')
      && !line.includes('defaultValue')
    ) {
      return true;
    }
    if (/\)\s*(\{|throws)/.test(line) && i > annotationLineIndex) {
      return false;
    }
  }
  return false;
}

function findClassLevelRequestMapping(lines: string[]): { path: string; lineIndex: number } | null {
  for (let i = 0; i < lines.length; i += 1) {
    if (!CLASS_DECLARATION.test(lines[i])) {
      continue;
    }
    for (let j = i - 1; j >= 0; j -= 1) {
      const candidate = lines[j].trim();
      if (candidate === '') {
        continue;
      }
      MAPPING_ANNOTATION.lastIndex = 0;
      const match = MAPPING_ANNOTATION.exec(candidate);
      // 直前の非空行が @RequestMapping("...") 以外なら、クラスレベルのパスは無い。
      return match && match[1] === 'Request' && match[2] != null
        ? { path: match[2], lineIndex: j }
        : null;
    }
    return null;
  }
  return null;
}

function joinPaths(base: string | undefined, sub: string): string {
  const basePath = base ?? '';
  let combined: string;
  if (sub === '') {
    combined = basePath;
  } else if (sub.startsWith('/')) {
    combined = basePath + sub;
  } else {
    combined = `${basePath}/${sub}`;
  }
  return (combined === '' ? '/' : combined).replace(/\/{2,}/g, '/');
}
