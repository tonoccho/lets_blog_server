import { loadWebview, WebviewHarness } from './support/webviewDom';

/**
 * Article Planパネル(webviews/plan.html + plan.js)のスラッグ検証(issue #1062)。
 *
 * サーバー/LLMが提案したスラッグは `articles/<slug>/` のディレクトリ名としてそのまま使われる。
 * `articleCreation.js` は入口で許可リスト方式の正規表現検証を行っているが、plan.js は
 * `title`/`slug` の空チェックしか持たず、`../../../evil` のようなパス区切りを含む値を
 * 拡張ホストへそのまま送っていた。ここでは入口側検証を articleCreation.js と揃える。
 */
describe('plan.js のスラッグ検証(issue #1062)', () => {
  let harness: WebviewHarness;

  beforeEach(() => {
    harness = loadWebview('plan');
    harness.element('titleInput').value = 'テスト記事';
  });

  function approvals(): Record<string, unknown>[] {
    return harness.posted.filter((m) => m.command === 'approveAndScaffold');
  }

  it('パス区切りを含むスラッグでの承認は拒否し、拡張ホストへ何も送らない', () => {
    harness.element('slugInput').value = '../../../evil';

    harness.element('approveButton').click();

    expect(approvals()).toEqual([]);
    const message = harness.element('message');
    expect(message.textContent).toBe('スラッグは半角英数字とハイフンのみで入力してください(先頭は英数字)。');
    expect(message.className).toBe('error');
  });

  it('正常なスラッグでの承認は従来どおり拡張ホストへ送られる', () => {
    harness.element('slugInput').value = 'my-article-01';

    harness.element('approveButton').click();

    expect(approvals()).toHaveLength(1);
    expect((approvals()[0].metadata as { slug: string }).slug).toBe('my-article-01');
  });

  it('タイトルとスラッグが空のときの既存メッセージは変わらない', () => {
    harness.element('titleInput').value = '';
    harness.element('slugInput').value = '';

    harness.element('approveButton').click();

    expect(approvals()).toEqual([]);
    expect(harness.element('message').textContent).toBe('タイトルとスラッグは必須です。');
  });
});
