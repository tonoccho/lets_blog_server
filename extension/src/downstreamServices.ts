/**
 * リクエストパスから「gatewayがそのリクエストを転送する下流サービス」を割り出す(issue #585)。
 *
 * サービス分割(Epic #551)後、拡張が受け取る5xxや接続失敗は「APIサーバーが落ちている」ではなく
 * 「どれか1つの下流サービスが落ちている」ことがほとんどになった。gatewayは応答本文に転送先の
 * サービス名を載せない(ProxyHandlerは下流の応答をそのまま中継し、タイムアウト時は本文の無い
 * 504を返す)ため、拡張側でパスからサービスを逆引きして利用者へ提示する。
 *
 * <b>このテーブルの位置づけ</b>: services/gateway/src/main/resources/application.yml のルート表の
 * 「拡張が実際に呼ぶパスに関係する部分だけ」を写したもので、gatewayの完全な複製ではない
 * (拡張が呼ばないルートは載せない)。ルート表と同じく<b>先勝ち</b>で評価するため、順序は
 * application.yml の並びに合わせてある。どれにもマッチしないパスは、gatewayの
 * fallback-uri と同じく legacy-api 宛として扱う。
 *
 * このモジュールは他の拡張内モジュールへ依存しない(errorHandler.tsから使うため。
 * config.ts → errorHandler.ts の依存があり、循環を避ける必要がある)。
 */

/** 下流サービスの識別子と、利用者向けの表示名。 */
export interface DownstreamService {
  /** docker-compose上のサービス名(ログやコンテナを探す手掛かりとして提示する)。 */
  id: string;
  /** 利用者向けの日本語名。 */
  label: string;
}

const AI: DownstreamService = { id: 'ai', label: 'AI生成サービス' };
const CONTENT: DownstreamService = { id: 'content', label: '記事コンテンツサービス' };
const MEDIA: DownstreamService = { id: 'media', label: '画像・ダイアグラムサービス' };
const PROJECT: DownstreamService = { id: 'project', label: 'プロジェクト管理サービス' };
const PUBLISHING: DownstreamService = { id: 'publishing', label: '記事公開サービス' };
const IDENTITY: DownstreamService = { id: 'identity', label: 'ユーザー管理サービス' };
const PLATFORM: DownstreamService = { id: 'platform', label: 'プラットフォームサービス' };
const LEGACY: DownstreamService = { id: 'api', label: 'APIサーバー(legacy-api)' };

/** gatewayそのもの(下流へ届く前に失敗した場合)。 */
export const GATEWAY: DownstreamService = { id: 'gateway', label: 'APIゲートウェイ' };

interface Route {
  /** Antスタイルのパスパターン。`*` は1セグメント、`**` は残り全体にマッチする。 */
  pattern: string;
  service: DownstreamService;
}

/**
 * application.yml と同じ順序(先勝ち)。拡張が呼ぶパスに関係するルートのみを写している。
 */
const ROUTES: Route[] = [
  // 記事プレビュー: /render のみ content-service、それ以外(theme-css/skeleton/preview-post)は
  // publishing-service(issue #712)。
  { pattern: '/api/projects/*/preview/render', service: CONTENT },
  { pattern: '/api/projects/*/preview/**', service: PUBLISHING },
  // 投稿の公開・削除は publishing-service(issue #707)。
  { pattern: '/api/posts/publish', service: PUBLISHING },
  { pattern: '/api/posts/*/*', service: PUBLISHING },
  // 記事プラン(壁打ちチャット・構成提案・Issue操作)は ai-service(issue #574/#659)。
  { pattern: '/api/projects/*/article-plan/**', service: AI },
  // 画像生成プロンプト生成は実装が legacy-api にしか無く、gateway にも専用ルートを追加した
  // (issue #771。それ以前は下の project ルートへ先勝ちマッチして404になっていた)。
  { pattern: '/api/projects/*/ai/generate-image-prompt', service: LEGACY },
  // 上記以外の /api/projects/** ・ /api/sites/** は project-service(issue #577 stage2)。
  { pattern: '/api/projects/**', service: PROJECT },
  { pattern: '/api/sites/**', service: PROJECT },
  // 記事本文の照会・カスタムタグ・メタデータ・コンテンツキャッシュは content-service(issue #576)。
  { pattern: '/api/posts/**', service: CONTENT },
  { pattern: '/api/custom-tags/**', service: CONTENT },
  { pattern: '/api/content-cache/**', service: CONTENT },
  { pattern: '/api/metadata/**', service: CONTENT },
  // 生成画像・ダイアグラムは media-service(issue #573)。
  { pattern: '/api/generated-images/**', service: MEDIA },
  { pattern: '/api/diagrams/**', service: MEDIA },
  // 画像生成(ComfyUI)だけは ai-service へ移設せず legacy-api に残っている(issue #574)。
  { pattern: '/api/ai/image', service: LEGACY },
  { pattern: '/api/ai/image-options', service: LEGACY },
  // それ以外のAI(下書き/校正/要約/タグ提案/セクション生成/Ask AI)は ai-service。
  { pattern: '/api/ai/**', service: AI },
  // ユーザー一覧は identity-service(issue #561)。
  { pattern: '/api/users/**', service: IDENTITY },
  // ログイン系は legacy-api に残る。
  { pattern: '/api/auth/**', service: LEGACY },
  // .vsix配布等のシステム系は platform-service(issue #696)。
  { pattern: '/api/system/**', service: PLATFORM },
];

/**
 * URLまたはパスから、gatewayが転送する下流サービスを返す。
 * どのルートにもマッチしない場合は、gatewayのfallback-uriと同じく legacy-api を返す。
 */
export function downstreamServiceFor(urlOrPath: string): DownstreamService {
  const path = pathOf(urlOrPath);
  for (const route of ROUTES) {
    if (matchesAntPattern(route.pattern, path)) {
      return route.service;
    }
  }
  return LEGACY;
}

/**
 * 絶対URLからパス部分だけを取り出す。クエリ文字列はルート判定に使わないため落とす
 * (gatewayのProxyHandlerもパスだけでルートを決める)。
 */
export function pathOf(urlOrPath: string): string {
  const withoutQuery = urlOrPath.split(/[?#]/)[0];
  const schemeEnd = withoutQuery.indexOf('://');
  if (schemeEnd < 0) {
    return withoutQuery;
  }
  const slash = withoutQuery.indexOf('/', schemeEnd + 3);
  return slash < 0 ? '/' : withoutQuery.slice(slash);
}

/**
 * Spring の AntPathMatcher のうち、ルート表が実際に使う機能(`*` = 1セグメント、
 * `**` = 残りの全セグメント)だけを再現する。gatewayのルート表は `?` や部分ワイルドカードを
 * 使っていないため、それらには対応しない。
 */
function matchesAntPattern(pattern: string, path: string): boolean {
  const patternSegments = pattern.split('/');
  const pathSegments = path.split('/');
  return matchSegments(patternSegments, 0, pathSegments, 0);
}

function matchSegments(
  pattern: string[],
  patternIndex: number,
  path: string[],
  pathIndex: number
): boolean {
  if (patternIndex === pattern.length) {
    return pathIndex === path.length;
  }
  const segment = pattern[patternIndex];
  if (segment === '**') {
    // 末尾の ** は残り全体にマッチする(空でもよい)。途中に現れる場合は全ての区切り位置を試す。
    for (let next = pathIndex; next <= path.length; next += 1) {
      if (matchSegments(pattern, patternIndex + 1, path, next)) {
        return true;
      }
    }
    return false;
  }
  if (pathIndex === path.length) {
    return false;
  }
  if (segment !== '*' && segment !== path[pathIndex]) {
    return false;
  }
  return matchSegments(pattern, patternIndex + 1, path, pathIndex + 1);
}
