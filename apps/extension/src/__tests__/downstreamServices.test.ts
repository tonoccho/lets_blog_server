import { downstreamServiceFor, pathOf } from '../downstreamServices';

describe('pathOf', () => {
  it('絶対URLからパスだけを取り出す', () => {
    expect(pathOf('https://localhost/api/sites')).toBe('/api/sites');
  });

  it('クエリ文字列を落とす', () => {
    expect(pathOf('https://localhost/api/diagrams?projectId=3')).toBe('/api/diagrams');
  });

  it('パスだけを渡した場合はそのまま返す', () => {
    expect(pathOf('/api/ai/draft')).toBe('/api/ai/draft');
  });

  it('ホストのみのURLは / を返す', () => {
    expect(pathOf('https://localhost')).toBe('/');
  });

  it('ポート番号付きのURLでもパスを取り違えない', () => {
    expect(pathOf('http://localhost:8080/api/posts/publish')).toBe('/api/posts/publish');
  });
});

/**
 * 拡張がapiClient.tsから実際に呼ぶ全パスについて、gatewayのルート表
 * (services/gateway/src/main/resources/application.yml)が転送する先と一致することを確認する。
 * ここが食い違うと、障害時に「担当サービス」として誤ったサービス名を利用者へ提示してしまう。
 */
describe('downstreamServiceFor', () => {
  const cases: [string, string][] = [
    // publishing-service(issue #707/#712)
    ['/api/posts/publish', 'publishing'],
    ['/api/posts/mysite/12345', 'publishing'],
    ['/api/projects/3/preview/skeleton', 'publishing'],
    ['/api/projects/3/preview/theme-css?siteId=1', 'publishing'],
    ['/api/projects/3/preview/preview-post?siteId=1&postId=9', 'publishing'],
    // content-service(issue #576)
    ['/api/projects/3/preview/render', 'content'],
    ['/api/posts/mysite/by-slug/my-article', 'content'],
    ['/api/metadata/post-statuses', 'content'],
    ['/api/metadata/roles', 'content'],
    ['/api/custom-tags?projectId=3', 'content'],
    ['/api/content-cache?url=https%3A%2F%2Fexample.com', 'content'],
    // ai-service(issue #574/#659)
    ['/api/ai/draft', 'ai'],
    ['/api/ai/ask', 'ai'],
    ['/api/ai/section', 'ai'],
    ['/api/ai/tags', 'ai'],
    ['/api/ai/proofread', 'ai'],
    ['/api/projects/3/article-plan/tags', 'ai'],
    ['/api/projects/3/article-plan/chat', 'ai'],
    ['/api/projects/3/article-plan/categories/hierarchy', 'ai'],
    ['/api/projects/3/article-plan/issues/12/assign', 'ai'],
    // 記事レビュー(PR一覧)は publishing-service(issue #1337)。project の catch-all より前。
    ['/api/projects/3/article-review/pull-requests', 'publishing'],
    // media-service(画像生成は issue #583 で legacy-api から media へ移設)
    ['/api/ai/image', 'media'],
    ['/api/ai/image-options?projectId=3', 'media'],
    ['/api/projects/3/ai-models/image/provider', 'media'],
    ['/api/projects/3/ai-models/comfyui/checkpoints', 'media'],
    ['/api/projects/3/image-settings', 'media'],
    // LLMモデル選択だけは ai-service(issue #574)
    ['/api/projects/3/ai-models/llm/models', 'ai'],
    // media-service(issue #573)
    ['/api/generated-images?projectId=3', 'media'],
    ['/api/generated-images/7/file', 'media'],
    ['/api/diagrams', 'media'],
    ['/api/diagrams/7/svg', 'media'],
    // project-service(issue #577 stage2)
    ['/api/projects', 'project'],
    ['/api/projects/3', 'project'],
    ['/api/sites', 'project'],
    // identity-service(issue #561、#583でメンバー管理と初回セットアップも移設)
    ['/api/users', 'identity'],
    ['/api/projects/3/users', 'identity'],
    ['/api/project-users', 'identity'],
    ['/api/auth/setup-status', 'identity'],
    // content-service(cssSelectorPrefix は #576 で content 所有、#583 でルートも content へ)
    ['/api/projects/3/css-selector-prefix', 'content'],
    ['/api/projects/3/content-settings', 'content'],
    // プロジェクト単位のAPIキーは #583 で所有サービスへ分割した
    ['/api/projects/3/api-keys/github-token', 'project'],
    ['/api/projects/3/api-keys/brave-search-api-key', 'ai'],
    ['/api/projects/3/api-keys/google-analytics', 'analytics'],
    ['/api/projects/3/api-keys/adsense', 'analytics'],
  ];

  it.each(cases)('%s -> %s', (path, expectedId) => {
    expect(downstreamServiceFor(path).id).toBe(expectedId);
  });

  it('絶対URLを渡してもパスから判定できる', () => {
    expect(downstreamServiceFor('https://localhost/api/ai/draft').id).toBe('ai');
  });

  /**
   * issue #583: legacy-api を削除し、gateway のフォールバック(未割り当てパスの暗黙転送)も
   * 廃止した。マッチしないパスは gateway 自身が404を返すので、存在しないコンテナ
   * (`lbs-api`)のログを見るよう案内してはいけない。
   */
  it('ルート表のどれにもマッチしないパスはgateway自身として扱う(issue #583)', () => {
    expect(downstreamServiceFor('/api/unknown-endpoint').id).toBe('gateway');
  });

  it('サービス名は利用者向けの日本語ラベルを持つ', () => {
    expect(downstreamServiceFor('/api/ai/draft').label).toBe('AI生成サービス');
  });

  /**
   * issue #771 で gateway に専用ルートを追加した(それ以前は広い /api/projects/** へ
   * 先勝ちマッチして404だった)。issue #583 で ai-service へ移設。
   * より広い /api/projects/** より前で判定される必要がある。
   */
  it('generate-image-promptはai-serviceへ向く(issue #771 / #583)', () => {
    expect(downstreamServiceFor('/api/projects/3/ai/generate-image-prompt').id).toBe('ai');
  });
});
