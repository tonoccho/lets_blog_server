import {
  computeBodyOffset,
  findFrontMatterFieldLine,
  findInvalidCategories,
  isValidStatus,
  locateContentIssues,
  REVIEW_STEPS,
  reviewProgressMessage,
  buildFindingHover,
  runReviewSteps,
} from '../proofreadLogic';
import { ProofreadIssue } from '../schemas';

function issue(overrides: Partial<ProofreadIssue>): ProofreadIssue {
  return { type: 'typo', originalText: '', message: '', suggestion: null, ...overrides };
}

describe('computeBodyOffset', () => {
  it('front matterブロックの直後の位置を返す', () => {
    const text = '---\ntitle: サンプル\n---\n本文です。';
    const offset = computeBodyOffset(text);
    expect(text.slice(offset)).toBe('本文です。');
  });

  it('front matterが無い場合は0を返す', () => {
    expect(computeBodyOffset('見出しのない本文だけ')).toBe(0);
  });
});

describe('locateContentIssues', () => {
  it('originalTextが本文中に見つかれば位置を解決する', () => {
    const content = 'こんちには世界。今日もこんちには元気です。';
    const bodyOffset = 4;
    const issues = [issue({ originalText: 'こんちには' })];

    const located = locateContentIssues(content, bodyOffset, issues);

    expect(located).toHaveLength(1);
    expect(located[0].startOffset).toBe(bodyOffset + content.indexOf('こんちには'));
    expect(located[0].endOffset).toBe(located[0].startOffset + 'こんちには'.length);
  });

  it('同じ表現が複数回登場する場合、指摘の順番通りに別の出現位置へ対応付ける', () => {
    const content = '猫が好き。犬も好き。猫が好き。';
    const issues = [issue({ originalText: '猫が好き' }), issue({ originalText: '猫が好き' })];

    const located = locateContentIssues(content, 0, issues);

    expect(located).toHaveLength(2);
    expect(located[0].startOffset).toBe(content.indexOf('猫が好き'));
    expect(located[1].startOffset).toBe(content.lastIndexOf('猫が好き'));
    expect(located[0].startOffset).not.toBe(located[1].startOffset);
  });

  it('本文中に存在しないoriginalTextの指摘は除外する', () => {
    const located = locateContentIssues('こんにちは世界', 0, [issue({ originalText: '本文に無い文字列' })]);
    expect(located).toEqual([]);
  });
});

describe('findFrontMatterFieldLine', () => {
  const text = ['---', 'title: サンプル', 'status: draft', 'categories:', '  - 技術', '---', '本文'].join('\n');

  it('front matter中のkeyが書かれている行を返す', () => {
    const location = findFrontMatterFieldLine(text, 'status');
    expect(location).toEqual({ line: 2, startColumn: 0, endColumn: 'status: draft'.length });
  });

  it('存在しないkeyはundefinedを返す', () => {
    expect(findFrontMatterFieldLine(text, 'publish_scheduled_at')).toBeUndefined();
  });

  it('front matterが無い場合はundefinedを返す', () => {
    expect(findFrontMatterFieldLine('見出しのない本文だけ', 'status')).toBeUndefined();
  });
});

describe('findInvalidCategories', () => {
  it('サイトに存在しないカテゴリを返す', () => {
    expect(findInvalidCategories(['技術', '存在しないカテゴリ'], ['技術', '日記'])).toEqual(['存在しないカテゴリ']);
  });

  it('大文字小文字を区別せず一致を判定する', () => {
    expect(findInvalidCategories(['Tech'], ['tech'])).toEqual([]);
  });

  it('既存カテゴリ一覧が空(取得失敗/サイト未紐付け)の場合はチェックをスキップする', () => {
    expect(findInvalidCategories(['技術'], [])).toEqual([]);
  });

  it('categories未設定の場合は空配列を返す', () => {
    expect(findInvalidCategories(undefined, ['技術'])).toEqual([]);
  });
});

describe('isValidStatus', () => {
  it('有効な値であればtrueを返す', () => {
    expect(isValidStatus('draft', ['draft', 'publish'])).toBe(true);
  });

  it('無効な値であればfalseを返す', () => {
    expect(isValidStatus('unknown', ['draft', 'publish'])).toBe(false);
  });

  it('status未設定の場合はtrueを返す', () => {
    expect(isValidStatus(undefined, ['draft', 'publish'])).toBe(true);
  });

  it('validValuesが空(取得失敗)の場合はチェックをスキップしtrueを返す', () => {
    expect(isValidStatus('unknown', [])).toBe(true);
  });
});

describe('REVIEW_STEPS', () => {
  it('5ステップを日本語チェック→校正チェック→校閲→読者視点でのチェック→文体チェックの順に持つ', () => {
    expect(REVIEW_STEPS.map((s) => s.key)).toEqual([
      'JAPANESE',
      'PROOFREADING',
      'FACT_CHECK',
      'READER_PERSPECTIVE',
      'STYLE',
    ]);
    expect(REVIEW_STEPS.map((s) => s.label)).toEqual([
      '日本語チェック',
      '校正チェック',
      '校閲',
      '読者視点でのチェック',
      '文体チェック',
    ]);
  });

  it('ステップごとに異なるテーマカラーが割り当てられている', () => {
    const colors = REVIEW_STEPS.map((s) => s.colorId);
    expect(new Set(colors).size).toBe(5);
    for (const id of colors) expect(id).toMatch(/^charts\./);
  });
});

describe('reviewProgressMessage', () => {
  it('現在のステップ名と進行位置を含める', () => {
    expect(reviewProgressMessage(1, 5, '校正チェック')).toBe('校正チェック (2/5)');
  });
});

describe('buildFindingHover', () => {
  it('ステップ名・指摘内容を含む', () => {
    const text = buildFindingHover('校閲', '事実と異なる可能性があります', null, []);
    expect(text).toContain('校閲');
    expect(text).toContain('事実と異なる可能性があります');
    expect(text).not.toContain('提案');
  });

  it('提案があれば読める形で含める', () => {
    const text = buildFindingHover('文体チェック', '口調が混在', 'です・ます調に統一', []);
    expect(text).toContain('提案');
    expect(text).toContain('です・ます調に統一');
  });

  it('出典があればタイトルとURLを含め、タイトルが無ければURLだけにする', () => {
    const text = buildFindingHover('校閲', 'm', undefined, [
      { title: '公式', url: 'https://a.test' },
      { url: 'https://b.test' },
    ]);
    expect(text).toContain('[公式](https://a.test)');
    expect(text).toContain('https://b.test');
  });
});

describe('runReviewSteps', () => {
  const content = 'AはBです。CはDです。';

  it('5ステップを順に実行し、各ステップの指摘を位置つきで集約する', async () => {
    const order: string[] = [];
    const progress: string[] = [];
    const result = await runReviewSteps({
      content,
      bodyOffset: 10,
      fetchStep: async (key) => {
        order.push(key);
        return key === 'JAPANESE'
          ? [{ stepKey: key, originalText: 'AはB', message: 'm1', suggestion: null, sources: [] }]
          : key === 'STYLE'
            ? [
                { stepKey: key, originalText: 'CはD', message: 'm2', suggestion: 'E', sources: [] },
                { stepKey: key, originalText: '存在しない', message: 'm3', suggestion: null, sources: [] },
              ]
            : [];
      },
      onStep: (step, index, total) => progress.push(`${index}/${total}:${step.label}`),
    });
    expect(order).toEqual(['JAPANESE', 'PROOFREADING', 'FACT_CHECK', 'READER_PERSPECTIVE', 'STYLE']);
    expect(progress).toEqual([
      '0/5:日本語チェック',
      '1/5:校正チェック',
      '2/5:校閲',
      '3/5:読者視点でのチェック',
      '4/5:文体チェック',
    ]);
    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({ step: { key: 'JAPANESE' }, startOffset: 10, endOffset: 13 });
    expect(result[1]).toMatchObject({ step: { key: 'STYLE' }, startOffset: 16, endOffset: 19 });
  });

  it('前のステップの完了を待ってから次のステップを呼ぶ(並列実行しない)', async () => {
    let running = 0;
    let maxRunning = 0;
    await runReviewSteps({
      content,
      bodyOffset: 0,
      fetchStep: async () => {
        running++;
        maxRunning = Math.max(maxRunning, running);
        await Promise.resolve();
        running--;
        return [];
      },
      onStep: () => undefined,
    });
    expect(maxRunning).toBe(1);
  });

  it('signalが中断済みなら次のステップへ進まず例外を投げる', async () => {
    const controller = new AbortController();
    const fetchStep = jest.fn(async () => {
      controller.abort();
      return [];
    });
    await expect(
      runReviewSteps({ content, bodyOffset: 0, fetchStep, onStep: () => undefined, signal: controller.signal })
    ).rejects.toThrow();
    expect(fetchStep).toHaveBeenCalledTimes(1);
  });
});
