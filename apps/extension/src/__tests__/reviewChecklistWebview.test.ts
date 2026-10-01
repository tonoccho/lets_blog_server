import { loadWebview, WebviewHarness } from './support/webviewDom';

/**
 * 指摘チェックリストパネル(webviews/reviewChecklist.html + reviewChecklist.js)のDOM挙動(issue #1216)。
 * 実際のクリック等のUI操作(見た目)はapps/extension/MANUAL_ACCEPTANCE_CHECKLIST.mdの担当だが、
 * `textContent`のみを使ってAI由来の文字列を描画すること(innerHTMLを使わないこと)と、
 * 対応状態を変えたら拡張ホストへ通知することはここで固定する。
 */
describe('reviewChecklist.js', () => {
  let harness: WebviewHarness;

  beforeEach(() => {
    harness = loadWebview('reviewChecklist');
  });

  function sendChecklist(groups: unknown[]): void {
    harness.postToWebview({ command: 'checklist', payload: { groups } });
  }

  it('ステップごとにグループ見出しと項目を描画する(AC1)', () => {
    sendChecklist([
      {
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        items: [
          { id: 'i1', stepKey: 'PROOFREADING', stepLabel: '校正チェック', originalText: 'AはBです', message: '誤字があります', suggestion: null, status: 'unresolved' },
        ],
      },
    ]);

    const groupsEl = harness.element('groups');
    expect(groupsEl.children).toHaveLength(1);
    const group = groupsEl.children[0];
    expect(group.children[0].textContent).toBe('校正チェック');
  });

  it('AI由来の文字列(引用文・指摘内容・提案)は必ずtextContent経由で描画する', () => {
    sendChecklist([
      {
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        items: [
          {
            id: 'i1',
            stepKey: 'PROOFREADING',
            stepLabel: '校正チェック',
            originalText: '<img src=x onerror=alert(1)>',
            message: '<script>alert(2)</script>',
            suggestion: '<b>提案</b>',
            status: 'unresolved',
          },
        ],
      },
    ]);

    const group = harness.element('groups').children[0];
    const item = group.children[1];
    const contentChildren = item.children.filter((c) => c.tagName !== 'select');
    const texts = contentChildren.map((c) => c.textContent).join('\n');
    expect(texts).toContain('<img src=x onerror=alert(1)>');
    expect(texts).toContain('<script>alert(2)</script>');
    expect(texts).toContain('<b>提案</b>');
    // innerHTMLとして解釈されていれば子要素(img/script/b)が生えるはずだが、生えていないこと。
    for (const child of contentChildren) {
      expect(child.children).toHaveLength(0);
    }
  });

  it('提案(suggestion)が無い項目には提案欄を作らない', () => {
    sendChecklist([
      {
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        items: [
          { id: 'i1', stepKey: 'PROOFREADING', stepLabel: '校正チェック', originalText: 'A', message: 'm', suggestion: null, status: 'unresolved' },
        ],
      },
    ]);

    const item = harness.element('groups').children[0].children[1];
    expect(item.className).not.toContain('suggestion');
    const texts = item.children.map((c) => c.className);
    expect(texts).not.toContain('suggestion');
  });

  it('対応状態を変更すると、拡張ホストへsetStatusを送り、表示のクラスも切り替わる(AC2)', () => {
    sendChecklist([
      {
        stepKey: 'PROOFREADING',
        stepLabel: '校正チェック',
        items: [
          { id: 'i1', stepKey: 'PROOFREADING', stepLabel: '校正チェック', originalText: 'A', message: 'm', suggestion: null, status: 'unresolved' },
        ],
      },
    ]);

    const item = harness.element('groups').children[0].children[1];
    const select = item.children.find((c) => c.tagName === 'select')!;
    expect(select.value).toBe('unresolved');

    select.value = 'fixed';
    select.dispatchEvent({ type: 'change' });

    expect(harness.posted).toEqual([{ command: 'setStatus', id: 'i1', status: 'fixed' }]);
    expect(item.className).toContain('status-fixed');
  });

  it('新しいchecklistメッセージを受け取ると前回の表示を消して描画し直す', () => {
    sendChecklist([
      { stepKey: 'PROOFREADING', stepLabel: '校正チェック', items: [
        { id: 'i1', stepKey: 'PROOFREADING', stepLabel: '校正チェック', originalText: 'A', message: 'm', suggestion: null, status: 'unresolved' },
      ] },
    ]);
    sendChecklist([]);

    expect(harness.element('groups').children).toHaveLength(0);
  });

  const oneItem = {
    stepKey: 'PROOFREADING',
    stepLabel: '校正チェック',
    items: [
      { id: 'i1', stepKey: 'PROOFREADING', stepLabel: '校正チェック', originalText: 'AはBです', message: 'm', suggestion: null, status: 'unresolved' },
    ],
  };

  function sendView(view: Record<string, unknown>): void {
    harness.postToWebview({ command: 'checklist', payload: view });
  }

  it('項目の引用文をクリックすると、拡張ホストへjumpを送る(issue #1225 AC1)', () => {
    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 1, isEmpty: false });

    const item = harness.element('groups').children[0].children[1];
    const jump = item.children.find((c) => c.className.includes('jump-target'))!;
    expect(jump.textContent).toBe('AはBです');
    jump.click();

    expect(harness.posted).toEqual([{ command: 'jump', id: 'i1' }]);
  });

  it('jumpNotFoundを受けると「本文に見つかりません」を表示し、次のchecklist受信で消す(AC2)', () => {
    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 1, isEmpty: false });

    harness.postToWebview({ command: 'jumpNotFound', payload: { message: '本文に見つかりません' } });
    expect(harness.element('jump-status').textContent).toBe('本文に見つかりません');

    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 1, isEmpty: false });
    expect(harness.element('jump-status').textContent).toBe('');
  });

  it('クリックのたびに前回の「見つかりません」表示を消す', () => {
    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 1, isEmpty: false });
    harness.postToWebview({ command: 'jumpNotFound', payload: { message: '本文に見つかりません' } });

    const item = harness.element('groups').children[0].children[1];
    item.children.find((c) => c.className.includes('jump-target'))!.click();

    expect(harness.element('jump-status').textContent).toBe('');
  });

  it('未対応の件数を一覧上に表示し、新しい件数を受けると追随する(AC3, AC4)', () => {
    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 3, isEmpty: false });
    expect(harness.element('summary').textContent).toContain('3');
    expect(harness.element('summary').textContent).toContain('未対応');

    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 2, isEmpty: false });
    expect(harness.element('summary').textContent).toContain('2');
    expect(harness.element('summary').textContent).not.toContain('3');
  });

  it('記録済みで指摘0件のときは、空であることを表示する(AC5)', () => {
    sendView({ groups: [], recorded: true, unresolvedCount: 0, isEmpty: true });

    expect(harness.element('empty').textContent).toContain('指摘はありません');
  });

  it('指摘があるとき・レビュー未実行のときは、空表示も件数表示も出さない', () => {
    sendView({ groups: [oneItem], recorded: true, unresolvedCount: 1, isEmpty: false });
    expect(harness.element('empty').textContent).toBe('');

    sendView({ groups: [], recorded: false, unresolvedCount: 0, isEmpty: false });
    expect(harness.element('empty').textContent).toBe('');
    expect(harness.element('summary').textContent).toBe('');
  });
});
