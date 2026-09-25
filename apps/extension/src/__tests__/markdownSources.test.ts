import { buildSourcesSection } from '../markdownSources';

describe('buildSourcesSection', () => {
  it('出典がある場合はMarkdownリンクの一覧を組み立てる', () => {
    const section = buildSourcesSection(
      [
        { title: 'Next.js 16 Docs', url: 'https://nextjs.org/docs' },
        { title: 'Example', url: 'https://example.com' },
      ],
      null
    );

    expect(section).toContain('**出典:**');
    expect(section).toContain('- [Next.js 16 Docs](https://nextjs.org/docs)');
    expect(section).toContain('- [Example](https://example.com)');
  });

  it('出典が空でsearchNoteがある場合はその注記のみを含める', () => {
    const section = buildSourcesSection([], 'Web検索を利用できなかったため、出典なしで生成しています');

    expect(section).toContain('Web検索を利用できなかったため、出典なしで生成しています');
    expect(section).not.toContain('出典:');
  });

  it('出典が空でsearchNoteも無い場合は空文字列を返す', () => {
    expect(buildSourcesSection([], null)).toBe('');
  });
});
