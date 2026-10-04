import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { CliGitBackend, GitBackend } from '../articleGit';
import {
  articleSlugOfPath,
  EDIT_ACTION,
  editRejectedArticle,
  FINDINGS_STATE_KEY,
  formatFindings,
  NO_COMMENT,
  RejectionFindings,
} from '../rejectionEdit';

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

const dirs: string[] = [];
afterEach(() => {
  while (dirs.length) fs.rmSync(dirs.pop()!, { recursive: true, force: true });
});

/** main をチェックアウトしたリポジトリ。branches の記事ブランチを(記事つきで)作って main へ戻る。 */
function repo(branches: string[]): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rej-edit-'));
  dirs.push(root);
  git(root, 'init', '-q', '-b', 'main');
  git(root, 'config', 'user.name', 't');
  git(root, 'config', 'user.email', 't@example.test');
  fs.writeFileSync(path.join(root, 'README.md'), 'hello');
  git(root, 'add', '.');
  git(root, 'commit', '-q', '-m', 'init');
  for (const branch of branches) {
    const slug = /^article\/\d+-(.+)$/.exec(branch)?.[1] ?? 'x';
    git(root, 'switch', '-q', '-c', branch);
    fs.mkdirSync(path.join(root, 'articles', slug), { recursive: true });
    fs.writeFileSync(path.join(root, 'articles', slug, 'article.md'), '# 記事');
    git(root, 'add', '.');
    git(root, 'commit', '-q', '-m', 'scaffold');
    git(root, 'switch', '-q', 'main');
  }
  return root;
}

function memoryState(): { get: jest.Mock; update: jest.Mock; data: Map<string, Record<string, string>> } {
  const data = new Map<string, Record<string, string>>();
  return {
    data,
    get: jest.fn((key: string) => data.get(key)),
    update: jest.fn(async (key: string, value: Record<string, string>) => void data.set(key, value)),
  };
}

function setup(root: string, comment: string | null = '見出しを直してください') {
  const state = memoryState();
  const findings = new RejectionFindings(state);
  const opened: string[] = [];
  const shown: { slug: string; comment: string }[] = [];
  return {
    state,
    findings,
    opened,
    shown,
    run: (slug = 'my-post') =>
      editRejectedArticle({
        root,
        review: { articleSlug: slug, rejectComment: comment },
        backend: new CliGitBackend(root),
        findings,
        openArticle: async (p) => void opened.push(p),
        showFindings: (s, c) => void shown.push({ slug: s, comment: c }),
      }),
  };
}

describe('editRejectedArticle', () => {
  it('記事ブランチへ切り替え、article.md を開き、指摘事項を表示・保持する', async () => {
    const root = repo(['article/42-my-post']);
    const t = setup(root);
    const result = await t.run();
    expect(result).toEqual({
      status: 'opened',
      branch: 'article/42-my-post',
      articlePath: path.join(root, 'articles', 'my-post', 'article.md'),
      switched: true,
    });
    expect(git(root, 'branch', '--show-current')).toBe('article/42-my-post');
    expect(t.opened).toEqual([path.join(root, 'articles', 'my-post', 'article.md')]);
    expect(t.shown).toEqual([{ slug: 'my-post', comment: '見出しを直してください' }]);
    expect(t.findings.get('my-post')).toBe('見出しを直してください');
  });

  it('指摘コメントが無い差し戻しでは代替の文言を保持する', async () => {
    const t = setup(repo(['article/42-my-post']), null);
    await t.run();
    expect(t.findings.get('my-post')).toBe(NO_COMMENT);
  });

  it('既にその記事ブランチにいれば、未コミットの変更があっても切り替えずに開く', async () => {
    const root = repo(['article/42-my-post']);
    git(root, 'switch', '-q', 'article/42-my-post');
    fs.appendFileSync(path.join(root, 'articles', 'my-post', 'article.md'), '\n編集中');
    const t = setup(root);
    const result = await t.run();
    expect(result).toMatchObject({ status: 'opened', switched: false });
    expect(t.opened).toHaveLength(1);
  });

  it('同じスラッグの記事ブランチが複数あれば、先頭のブランチを使う', async () => {
    const root = repo(['article/9-my-post', 'article/42-my-post']);
    const t = setup(root);
    expect(await t.run()).toMatchObject({ branch: 'article/42-my-post' });
  });

  it('スラッグが一致しない記事ブランチ・記事用でないブランチは対象にしない', async () => {
    const root = repo(['article/42-my-post-2', 'article/x']);
    git(root, 'branch', 'feature/my-post');
    const t = setup(root);
    const result = await t.run();
    expect(result).toMatchObject({ status: 'not-in-workspace', reason: expect.stringContaining('my-post') });
    expect(git(root, 'branch', '--show-current')).toBe('main');
    expect(t.opened).toEqual([]);
    expect(t.shown).toEqual([]);
    expect(t.findings.get('my-post')).toBeUndefined();
  });

  it('未コミットの変更があれば切り替えず、理由を返し、エディタも開かない', async () => {
    const root = repo(['article/42-my-post']);
    fs.writeFileSync(path.join(root, 'README.md'), 'dirty');
    const t = setup(root);
    const result = await t.run();
    expect(result).toMatchObject({ status: 'dirty', reason: expect.stringContaining('未コミット') });
    expect(git(root, 'branch', '--show-current')).toBe('main');
    expect(t.opened).toEqual([]);
    expect(t.shown).toEqual([]);
  });

  it('gitリポジトリでなければ切り替えず理由を返す', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rej-edit-nogit-'));
    dirs.push(root);
    const backend: GitBackend = new CliGitBackend(root);
    const opened: string[] = [];
    const result = await editRejectedArticle({
      root,
      review: { articleSlug: 'my-post', rejectComment: 'x' },
      backend: Object.assign(Object.create(backend), { isRepository: async () => false }),
      findings: new RejectionFindings(memoryState()),
      openArticle: async (p) => void opened.push(p),
      showFindings: () => undefined,
    });
    expect(result).toMatchObject({ status: 'not-repository' });
    expect(opened).toEqual([]);
  });
});

describe('CliGitBackend.listBranches', () => {
  it('prefix で始まるローカルブランチだけを返す', async () => {
    const root = repo(['article/1-a', 'article/2-b']);
    git(root, 'branch', 'other');
    expect(await new CliGitBackend(root).listBranches('article/')).toEqual(['article/1-a', 'article/2-b']);
  });
});

describe('RejectionFindings', () => {
  it('指摘事項は状態へ保存され、新しいインスタンスからも読める', async () => {
    const state = memoryState();
    await new RejectionFindings(state).record('a', '直して');
    expect(state.data.get(FINDINGS_STATE_KEY)).toEqual({ a: '直して' });
    expect(new RejectionFindings(state).get('a')).toBe('直して');
    expect(new RejectionFindings(state).get('b')).toBeUndefined();
  });

  it('同じ記事を記録し直すと上書きされ、上限を超えると古い記事から捨てる', async () => {
    const state = memoryState();
    const f = new RejectionFindings(state);
    await f.record('a', '1');
    await f.record('a', '2');
    expect(f.get('a')).toBe('2');
    for (let i = 0; i < 100; i++) await f.record(`s${i}`, 'c');
    expect(f.get('a')).toBeUndefined();
    expect(f.get('s99')).toBe('c');
    expect(Object.keys(state.data.get(FINDINGS_STATE_KEY)!)).toHaveLength(100);
  });
});

describe('補助関数', () => {
  it('articleSlugOfPath は articles/<slug>/article.md からスラッグを取り出す', () => {
    expect(articleSlugOfPath('/r/articles/my-post/article.md')).toBe('my-post');
    expect(articleSlugOfPath('C:\\r\\articles\\my-post\\article.md')).toBe('my-post');
    expect(articleSlugOfPath('/r/articles/my-post/notes.md')).toBeUndefined();
    expect(articleSlugOfPath('/r/README.md')).toBeUndefined();
  });

  it('formatFindings は記事のスラッグと指摘事項を含む', () => {
    expect(formatFindings('my-post', '直して')).toContain('my-post');
    expect(formatFindings('my-post', '直して')).toContain('直して');
  });

  it('通知の選択肢は「編集」', () => {
    expect(EDIT_ACTION).toBe('編集');
  });
});
