import * as vscode from 'vscode';
import { lastCreatedWebviewPanel, resetMocks } from '../__mocks__/vscode';
import { PreviewMessage, PreviewPanel, SiteOption } from '../previewPanel';

const fakeContext = { extensionUri: 'file:///ext' } as unknown as vscode.ExtensionContext;

describe('PreviewPanel', () => {
  afterEach(() => {
    // シングルトンをテスト間で共有しない(前のテストで開いたパネルが次のテストへ漏れないように)。
    PreviewPanel.currentPanel = undefined;
    resetMocks();
  });

  it('環境が1つ以下の場合はセレクトを描画せず、静的なサイトラベルを表示する', () => {
    PreviewPanel.createOrShow(fakeContext, '<p>本文</p>', 'body{color:red}', undefined, '本番 / example.com');

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain('適用中のCSS: 本番 / example.com');
    expect(html).not.toContain('letsblog-site-switcher');
  });

  it('環境が2つ以上ある場合はセレクトを描画し、現在のサイトが選択済みになる', () => {
    const availableSites: SiteOption[] = [
      { siteId: 1, label: 'ローカル', siteName: 'local.example.com' },
      { siteId: 2, label: 'テスト', siteName: 'test.example.com' },
    ];

    PreviewPanel.createOrShow(
      fakeContext,
      '<p>本文</p>',
      'body{color:red}',
      undefined,
      'テスト / test.example.com',
      undefined,
      undefined,
      availableSites,
      2
    );

    const html = lastCreatedWebviewPanel?.webview.html ?? '';
    expect(html).toContain('id="letsblog-site-switcher"');
    expect(html).toContain('value="1"');
    expect(html).toContain('value="2" selected');
  });

  it('WebviewからswitchSiteメッセージを受け取ると、渡したハンドラーへ転送する', () => {
    const availableSites: SiteOption[] = [
      { siteId: 1, label: 'ローカル', siteName: 'local.example.com' },
      { siteId: 2, label: 'テスト', siteName: 'test.example.com' },
    ];
    const received: PreviewMessage[] = [];

    PreviewPanel.createOrShow(
      fakeContext,
      '<p>本文</p>',
      'body{color:red}',
      undefined,
      'ローカル / local.example.com',
      undefined,
      (message) => received.push(message),
      availableSites,
      1
    );

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ type: 'switchSite', siteId: 2 });

    expect(received).toEqual([{ type: 'switchSite', siteId: 2 }]);
  });

  it('既に開いている場合は同じパネルを再利用し、内容とハンドラーを差し替える', () => {
    PreviewPanel.createOrShow(fakeContext, '<p>1つ目</p>', '', undefined, '本番 / example.com');
    const firstPanel = lastCreatedWebviewPanel;

    PreviewPanel.createOrShow(fakeContext, '<p>2つ目</p>', '', undefined, 'テスト / test.example.com');

    expect(lastCreatedWebviewPanel).toBe(firstPanel);
    expect(firstPanel?.webview.html).toContain('2つ目');
    expect(firstPanel?.webview.html).toContain('適用中のCSS: テスト / test.example.com');
  });
});
