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
    // legacy-api(画像生成だけはai-serviceへ移設されていない、issue #574)
    ['/api/ai/image', 'api'],
    ['/api/ai/image-options?projectId=3', 'api'],
    // media-service(issue #573)
    ['/api/generated-images?projectId=3', 'media'],
    ['/api/generated-images/7/file', 'media'],
    ['/api/diagrams', 'media'],
    ['/api/diagrams/7/svg', 'media'],
    // project-service(issue #577 stage2)
    ['/api/projects', 'project'],
    ['/api/projects/3', 'project'],
    ['/api/sites', 'project'],
    // identity-service(issue #561)
    ['/api/users', 'identity'],
  ];

  it.each(cases)('%s -> %s', (path, expectedId) => {
    expect(downstreamServiceFor(path).id).toBe(expectedId);
  });

  it('絶対URLを渡してもパスから判定できる', () => {
    expect(downstreamServiceFor('https://localhost/api/ai/draft').id).toBe('ai');
  });

  it('ルート表のどれにもマッチしないパスはfallback-uriと同じlegacy-apiとして扱う', () => {
    expect(downstreamServiceFor('/api/unknown-endpoint').id).toBe('api');
  });

  it('サービス名は利用者向けの日本語ラベルを持つ', () => {
    expect(downstreamServiceFor('/api/ai/draft').label).toBe('AI生成サービス');
  });

  /**
   * issue #771: 実装はlegacy-apiにしか無いが、gatewayに専用ルートが無いため
   * project-serviceへ先勝ちマッチしている(=実際に転送される先はproject-service)。
   * ここでも「実際の転送先」を返すことで、エラーメッセージが実態とずれないようにする。
   */
  it('generate-image-promptはgatewayの実際の挙動どおりproject-serviceへ向く(issue #771)', () => {
    expect(downstreamServiceFor('/api/projects/3/ai/generate-image-prompt').id).toBe('project');
  });
});
