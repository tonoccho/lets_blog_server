import * as vscode from 'vscode';
import { lastCreatedWebviewPanel, openedExternalUrls, resetMocks } from '../__mocks__/vscode';
import { PreviewMessage, PreviewPanel, SiteOption } from '../previewPanel';

const fakeContext = { extensionUri: 'file:///ext' } as unknown as vscode.ExtensionContext;

describe('PreviewPanel 旧方式の表示の削除(issue #1564)', () => {
  afterEach(() => {
    PreviewPanel.currentPanel = undefined;
    resetMocks();
  });

  it('テーマCSSを埋め込む旧方式の表示(createOrShow)は持たない', () => {
    expect(PreviewPanel).not.toHaveProperty('createOrShow');
  });

  it('サイトが紐づいていないプロジェクトでは、iframeもテーマCSSも出さず、サイトを紐づける案内を表示する', () => {
    PreviewPanel.showNoSite(fakeContext);

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).not.toContain('<iframe');
    expect(html).not.toContain('Prism');
    expect(html).toContain('サイトが紐づいていません');
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview');
  });

  it('案内表示のあとに再度開くと、同じパネルを再利用する', () => {
    PreviewPanel.showNoSite(fakeContext);
    const panel = lastCreatedWebviewPanel;

    PreviewPanel.showNoSite(fakeContext);

    expect(lastCreatedWebviewPanel).toBe(panel);
  });
});

describe('PreviewPanel 実サイトのプレビュー(issue #1562)', () => {
  const url = 'https://blog.example.com/?letsblog_preview=tok';

  afterEach(() => {
    PreviewPanel.currentPanel = undefined;
    resetMocks();
  });

  it('プレビューURLをiframeで表示し、CSPのframe-srcはそのオリジンだけを許可する', () => {
    PreviewPanel.showRealSite(fakeContext, { url, siteLabel: '本番 / blog' });

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain(`<iframe`);
    expect(html).toContain(`src="${url}"`);
    expect(html).toContain('frame-src https://blog.example.com;');
    expect(html).not.toMatch(/frame-src[^;]*\*/);
  });

  it('埋め込みが拒否されたときのため、外部ブラウザで開く操作を常に表示する', () => {
    PreviewPanel.showRealSite(fakeContext, { url });

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain('id="letsblog-open-external"');
    expect(html).toContain('外部ブラウザで開く');
  });

  it('openExternalメッセージを受けると、表示中のURLを外部ブラウザで開く', () => {
    PreviewPanel.showRealSite(fakeContext, { url });

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'openExternal' });

    expect(openedExternalUrls).toEqual([url]);
  });

  it('再プレビューでは同じパネルが新しいURLに差し替わり、外部で開く先も新しいURLになる', () => {
    PreviewPanel.showRealSite(fakeContext, { url });
    const panel = lastCreatedWebviewPanel;
    const newUrl = 'https://blog.example.com/?letsblog_preview=tok2';

    PreviewPanel.showRealSite(fakeContext, { url: newUrl });
    panel?.webview.postMessageToExtension({ type: 'openExternal' });

    expect(lastCreatedWebviewPanel).toBe(panel);
    expect(panel?.webview.html).toContain(`src="${newUrl}"`);
    expect(panel?.webview.html).not.toContain('tok"');
    expect(openedExternalUrls).toEqual([newUrl]);
  });

  it('switchSiteメッセージは渡したハンドラーへ転送し、環境が複数ならセレクトを表示する', () => {
    const received: PreviewMessage[] = [];
    const availableSites: SiteOption[] = [
      { siteId: 1, label: 'テスト', siteName: 't' },
      { siteId: 2, label: '本番', siteName: 'p' },
    ];
    PreviewPanel.showRealSite(fakeContext, {
      url,
      onMessage: (m) => received.push(m),
      availableSites,
      currentSiteId: 2,
    });

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'switchSite', siteId: 1 });

    expect(received).toEqual([{ type: 'switchSite', siteId: 1 }]);
    expect(lastCreatedWebviewPanel?.webview.html).toContain('value="2" selected');
  });

  it('http/https以外のURLは外部ブラウザで開かない', () => {
    PreviewPanel.showRealSite(fakeContext, { url });
    // 表示後にURLが書き換わったように見せかけるメッセージでも、保持しているURLしか開かない。
    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'openExternal', url: 'file:///etc/passwd' });

    expect(openedExternalUrls).toEqual([url]);
  });

  it('プラグイン未導入のサイトでは、iframeを出さず導入の案内を表示する', () => {
    PreviewPanel.showPluginGuidance(fakeContext, { needsUpdate: false, siteLabel: '本番 / blog' });

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).not.toContain('<iframe');
    expect(html).not.toContain('frame-src');
    expect(html).toContain('プラグインが導入されていません');
    expect(html).toContain('プラグインを再導入');
  });

  it('プラグインが要更新のサイトでは、更新の案内を表示する', () => {
    PreviewPanel.showPluginGuidance(fakeContext, { needsUpdate: true });

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain('プラグインの更新が必要です');
    expect(html).not.toContain('<iframe');
  });

  it('案内表示でも環境切り替えのハンドラーとセレクトが使える', () => {
    const received: PreviewMessage[] = [];
    PreviewPanel.showPluginGuidance(fakeContext, {
      needsUpdate: false,
      onMessage: (m) => received.push(m),
      availableSites: [
        { siteId: 1, label: 'テスト', siteName: 't' },
        { siteId: 2, label: '本番', siteName: 'p' },
      ],
      currentSiteId: 1,
    });

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'switchSite', siteId: 2 });

    expect(received).toEqual([{ type: 'switchSite', siteId: 2 }]);
  });

  it('案内表示のあと実サイトのURLに切り替わると、外部で開く先はそのURLになる', () => {
    PreviewPanel.showPluginGuidance(fakeContext, { needsUpdate: false });
    PreviewPanel.showRealSite(fakeContext, { url });

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'openExternal' });

    expect(openedExternalUrls).toEqual([url]);
  });

  it('URLが無い状態でopenExternalを受けても何も開かない', () => {
    PreviewPanel.showPluginGuidance(fakeContext, { needsUpdate: false });

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'openExternal' });

    expect(openedExternalUrls).toEqual([]);
  });

  it('サーバーが理由を返した場合は案内に添え、HTMLとして解釈させない', () => {
    PreviewPanel.showPluginGuidance(fakeContext, { needsUpdate: false, message: '未導入 <b>x</b>', siteLabel: '本番 / blog' });

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain('未導入 &lt;b&gt;x&lt;/b&gt;');
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview (本番 / blog)');
  });

  it('ハンドラー未指定でもswitchSiteメッセージで例外にならず、ラベル無しの題名になる', () => {
    PreviewPanel.showRealSite(fakeContext, { url });

    expect(() => lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'switchSite', siteId: 1 })).not.toThrow();
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview');
  });

  it('サイトラベルを渡すと静的なバナーに表示する', () => {
    PreviewPanel.showRealSite(fakeContext, { url, siteLabel: '本番 / <blog>' });

    expect(lastCreatedWebviewPanel?.webview.html).toContain('プレビュー: 本番 / &lt;blog&gt;');
  });

  it('サイト未紐付け(siteId=null)の選択肢も、セレクトの値は空文字になる', () => {
    PreviewPanel.showRealSite(fakeContext, {
      url,
      availableSites: [
        { siteId: null, label: 'サイトなし', siteName: '未紐付け' },
        { siteId: 2, label: '本番', siteName: 'p' },
      ],
      currentSiteId: null,
    });

    expect(lastCreatedWebviewPanel?.webview.html).toContain('value="" selected');
  });
});
