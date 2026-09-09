import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  openedDocuments,
  resetMocks,
  setWarningResponse,
  setWorkspaceFolders,
  shownWarnings,
} from '../__mocks__/vscode';
import { createArticleScaffold, openArticle, requireWorkspaceRoot } from '../articleScaffold';
import { parseArticle } from '../frontMatter';

/**
 * 記事ディレクトリの生成(issue #942 / AT-16 Layer 2)。
 * 「Create Article (No AI)」の実体はローカルのファイル生成であり、サーバーを介さないため
 * Layer 1(APIレベル)では観測できない。ここで生成物の形を固定する。
 */
describe('createArticleScaffold', () => {
  let workspaceRoot: string;

  beforeEach(() => {
    workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-scaffold-'));
  });

  afterEach(() => {
    fs.rmSync(workspaceRoot, { recursive: true, force: true });
    resetMocks();
  });

  it('articles/<slug>/ に article.md と assets/ を生成する', async () => {
    const result = await createArticleScaffold({
      workspaceRoot,
      slug: 'my-article',
      frontMatter: { title: '記事タイトル', slug: 'my-article', status: 'draft' },
      content: '# 見出し\n\n本文',
    });

    expect(result).toBeDefined();
    expect(result?.articleDir).toBe(path.join(workspaceRoot, 'articles', 'my-article'));
    expect(fs.existsSync(path.join(result!.articleDir, 'assets', '.gitkeep'))).toBe(true);

    const parsed = parseArticle(fs.readFileSync(result!.articlePath, 'utf-8'));
    expect(parsed.data).toMatchObject({ title: '記事タイトル', slug: 'my-article', status: 'draft' });
    expect(parsed.content.trim()).toBe('# 見出し\n\n本文');
  });

  it('本文が空の場合は記入を促すプレースホルダを入れる', async () => {
    const result = await createArticleScaffold({
      workspaceRoot,
      slug: 'empty',
      frontMatter: { title: '空の記事' },
      content: '   \n  ',
    });

    const parsed = parseArticle(fs.readFileSync(result!.articlePath, 'utf-8'));
    expect(parsed.content.trim()).toBe('記事本文をここに記入してください。');
  });

  it('日本語のfront matterをそのまま往復できる', async () => {
    const result = await createArticleScaffold({
      workspaceRoot,
      slug: 'japanese',
      frontMatter: { title: '日本語のタイトル', categories: ['技術'], tags: ['入門', 'テスト'] },
      content: '本文',
    });

    const parsed = parseArticle(fs.readFileSync(result!.articlePath, 'utf-8'));
    expect(parsed.data.title).toBe('日本語のタイトル');
    expect(parsed.data.categories).toEqual(['技術']);
    expect(parsed.data.tags).toEqual(['入門', 'テスト']);
  });

  it('既存ディレクトリがあるとき、上書きに同意すれば作り直す', async () => {
    const articleDir = path.join(workspaceRoot, 'articles', 'existing');
    fs.mkdirSync(articleDir, { recursive: true });
    fs.writeFileSync(path.join(articleDir, 'article.md'), '古い内容');
    setWarningResponse('Yes');

    const result = await createArticleScaffold({
      workspaceRoot,
      slug: 'existing',
      frontMatter: { title: '新しい記事' },
      content: '新しい本文',
    });

    expect(shownWarnings[0]).toContain('articles/existing は既に存在します');
    expect(fs.readFileSync(result!.articlePath, 'utf-8')).toContain('新しい本文');
  });

  it('上書きを断ると何も書き換えない', async () => {
    const articleDir = path.join(workspaceRoot, 'articles', 'keep');
    fs.mkdirSync(articleDir, { recursive: true });
    fs.writeFileSync(path.join(articleDir, 'article.md'), '古い内容');
    setWarningResponse('No');

    const result = await createArticleScaffold({
      workspaceRoot,
      slug: 'keep',
      frontMatter: { title: '新しい記事' },
      content: '新しい本文',
    });

    expect(result).toBeUndefined();
    expect(fs.readFileSync(path.join(articleDir, 'article.md'), 'utf-8')).toBe('古い内容');
  });

  /**
   * 呼び出し元(articleCreation.js / extension.ts / plan.js)ごとの入口検証は片方が抜け得るため
   * (issue #1062)、ディレクトリ名を組み立てるここ自身も最終防衛線として検証する。
   */
  it.each(['../../../evil', 'a/b', '..', ''])(
    '不正なスラッグ(%s)は例外を投げ、何も作られない',
    async (slug) => {
      await expect(
        createArticleScaffold({
          workspaceRoot,
          slug,
          frontMatter: { title: '記事タイトル' },
          content: '本文',
        })
      ).rejects.toThrow('スラッグは半角英数字とハイフンのみで入力してください(先頭は英数字)。');

      expect(fs.readdirSync(workspaceRoot)).toEqual([]);
    }
  );

  it('確認ダイアログを閉じた場合も上書きしない', async () => {
    const articleDir = path.join(workspaceRoot, 'articles', 'dismissed');
    fs.mkdirSync(articleDir, { recursive: true });
    fs.writeFileSync(path.join(articleDir, 'article.md'), '古い内容');
    setWarningResponse(undefined);

    await expect(
      createArticleScaffold({
        workspaceRoot,
        slug: 'dismissed',
        frontMatter: { title: '新しい記事' },
        content: '本文',
      })
    ).resolves.toBeUndefined();
  });
});

describe('openArticle', () => {
  afterEach(() => resetMocks());

  it('生成した記事をエディタで開く', async () => {
    await openArticle('/tmp/articles/x/article.md');
    expect(openedDocuments).toContain('/tmp/articles/x/article.md');
  });
});

describe('requireWorkspaceRoot', () => {
  afterEach(() => resetMocks());

  it('開いているワークスペースのパスを返す', () => {
    setWorkspaceFolders([{ uri: { fsPath: '/work/blog' } }]);
    expect(requireWorkspaceRoot()).toBe('/work/blog');
  });

  it('ワークスペースが開かれていない場合は対処方法を含む例外を投げる', () => {
    setWorkspaceFolders(undefined);
    expect(() => requireWorkspaceRoot()).toThrow('ワークスペースフォルダが開かれていません');
  });
});
