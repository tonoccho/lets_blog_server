import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  buildBranchName,
  buildCommitMessage,
  CliGitBackend,
  GitBackend,
  scaffoldArticleOnBranch,
} from '../articleGit';
import { VscodeGitBackend } from '../vscodeGit';

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

function initRepo(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'article-git-'));
  git(dir, 'init', '-q', '-b', 'main');
  git(dir, 'config', 'user.name', 'tester');
  git(dir, 'config', 'user.email', 'tester@example.test');
  fs.writeFileSync(path.join(dir, 'README.md'), 'hello');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'init');
  return dir;
}

/** createArticleScaffold の代わりに、同じ形のファイルを書く。 */
function fakeScaffold(root: string, slug: string) {
  return async () => {
    const articleDir = path.join(root, 'articles', slug);
    fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
    fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');
    const articlePath = path.join(articleDir, 'article.md');
    fs.writeFileSync(articlePath, '# t');
    return { articleDir, articlePath };
  };
}

describe('buildBranchName / buildCommitMessage', () => {
  it('Issue番号とスラッグから決定的に組み立てる', () => {
    expect(buildBranchName(12, 'my-slug')).toBe('article/12-my-slug');
    expect(buildBranchName(12, 'my-slug')).toBe(buildBranchName(12, 'my-slug'));
  });

  it('コミットメッセージに記事タイトルとIssue番号を含める', () => {
    const message = buildCommitMessage('タイトル', 12);
    expect(message).toContain('タイトル');
    expect(message).toContain('#12');
  });
});

describe('scaffoldArticleOnBranch (実git)', () => {
  const dirs: string[] = [];
  afterEach(() => {
    while (dirs.length) fs.rmSync(dirs.pop()!, { recursive: true, force: true });
  });
  function repo(): string {
    const d = initRepo();
    dirs.push(d);
    return d;
  }
  const base = { issueNumber: 7, slug: 'my-post', title: '私の記事' };

  it('ブランチを作って雛形を1コミットで記録し、未コミットの変更を残さない', async () => {
    const root = repo();
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold: fakeScaffold(root, 'my-post'),
      chooseOnExistingBranch: async () => 'abort',
    });
    expect(result.status).toBe('created');
    expect(git(root, 'branch', '--show-current')).toBe('article/7-my-post');
    expect(git(root, 'status', '--porcelain')).toBe('');
    expect(git(root, 'show', '--name-only', '--format=%s', 'HEAD').split('\n')).toEqual([
      buildCommitMessage('私の記事', 7),
      '',
      'articles/my-post/article.md',
      'articles/my-post/assets/.gitkeep',
    ]);
  });

  it('gitリポジトリでなければ理由を返し、ファイルを作らない', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'article-nogit-'));
    dirs.push(root);
    const scaffold = jest.fn(fakeScaffold(root, 'my-post'));
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold,
      chooseOnExistingBranch: async () => 'abort',
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('git') });
    expect(scaffold).not.toHaveBeenCalled();
    expect(fs.existsSync(path.join(root, 'articles'))).toBe(false);
  });

  it('未コミットの変更があれば理由を返し、ブランチを作らない', async () => {
    const root = repo();
    fs.writeFileSync(path.join(root, 'README.md'), 'dirty');
    const scaffold = jest.fn(fakeScaffold(root, 'my-post'));
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold,
      chooseOnExistingBranch: async () => 'switch',
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('未コミット') });
    expect(git(root, 'branch', '--list', 'article/*')).toBe('');
    expect(scaffold).not.toHaveBeenCalled();
  });

  it('同名ブランチがあり中断を選ぶと、ブランチもファイルも変化しない', async () => {
    const root = repo();
    git(root, 'branch', 'article/7-my-post');
    const scaffold = jest.fn(fakeScaffold(root, 'my-post'));
    const choose = jest.fn(async () => 'abort' as const);
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold,
      chooseOnExistingBranch: choose,
    });
    expect(choose).toHaveBeenCalledWith('article/7-my-post');
    expect(result.status).toBe('aborted');
    expect(git(root, 'branch', '--show-current')).toBe('main');
    expect(scaffold).not.toHaveBeenCalled();
  });

  it('同名ブランチがあり切り替えを選ぶと、そのブランチへ切り替えて雛形をコミットする', async () => {
    const root = repo();
    git(root, 'branch', 'article/7-my-post');
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold: fakeScaffold(root, 'my-post'),
      chooseOnExistingBranch: async () => 'switch',
    });
    expect(result).toMatchObject({ status: 'created', switched: true });
    expect(git(root, 'branch', '--show-current')).toBe('article/7-my-post');
    expect(git(root, 'status', '--porcelain')).toBe('');
  });

  it('雛形生成が取り消された場合は cancelled を返し、コミットしない', async () => {
    const root = repo();
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold: async () => undefined,
      chooseOnExistingBranch: async () => 'abort',
    });
    expect(result.status).toBe('cancelled');
    expect(git(root, 'log', '--oneline', '-n', '5').split('\n')).toHaveLength(1);
  });

  it('既定ブランチが origin/HEAD で示される場合、その最新から切る', async () => {
    const upstream = repo();
    git(upstream, 'checkout', '-q', '-b', 'develop');
    fs.writeFileSync(path.join(upstream, 'new.txt'), 'x');
    git(upstream, 'add', '.');
    git(upstream, 'commit', '-q', '-m', 'develop-only');
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'article-clone-'));
    dirs.push(root);
    git(root, 'clone', '-q', upstream, '.');
    git(root, 'config', 'user.name', 't');
    git(root, 'config', 'user.email', 't@example.test');
    // 上流にさらに進んだコミットを積む(clone側はまだ知らない)
    fs.writeFileSync(path.join(upstream, 'newer.txt'), 'y');
    git(upstream, 'add', '.');
    git(upstream, 'commit', '-q', '-m', 'newer');
    git(root, 'checkout', '-q', '-b', 'scratch');

    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold: fakeScaffold(root, 'my-post'),
      chooseOnExistingBranch: async () => 'abort',
    });
    expect(result.status).toBe('created');
    expect(git(root, 'log', '--format=%s', '-n', '3')).toContain('newer');
  });

  it('既定ブランチの手掛かりが無ければ現在のブランチから切る', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'article-odd-'));
    dirs.push(root);
    git(root, 'init', '-q', '-b', 'trunk');
    git(root, 'config', 'user.name', 't');
    git(root, 'config', 'user.email', 't@example.test');
    fs.writeFileSync(path.join(root, 'a'), 'a');
    git(root, 'add', '.');
    git(root, 'commit', '-q', '-m', 'init');
    const result = await scaffoldArticleOnBranch({
      ...base,
      root,
      backend: new CliGitBackend(root),
      scaffold: fakeScaffold(root, 'my-post'),
      chooseOnExistingBranch: async () => 'abort',
    });
    expect(result.status).toBe('created');
  });
});

describe('VscodeGitBackend', () => {
  it('ブランチ作成・切り替え・ステージ・コミットをGit拡張のRepositoryへ委譲する', async () => {
    const root = initRepoForVscode();
    const calls: unknown[][] = [];
    const repository = {
      createBranch: async (...a: unknown[]) => void calls.push(['createBranch', ...a]),
      checkout: async (...a: unknown[]) => void calls.push(['checkout', ...a]),
      add: async (...a: unknown[]) => void calls.push(['add', ...a]),
      commit: async (...a: unknown[]) => void calls.push(['commit', ...a]),
    };
    const backend: GitBackend = new VscodeGitBackend(root, repository);
    await backend.createBranch('article/1-a', 'main');
    await backend.checkout('article/1-a');
    await backend.commit(['articles/a/article.md'], 'msg');
    expect(calls).toEqual([
      ['createBranch', 'article/1-a', true, 'main'],
      ['checkout', 'article/1-a'],
      ['add', [path.join(root, 'articles/a/article.md')]],
      ['commit', 'msg'],
    ]);
    // 問い合わせ系はCLI実装を引き継ぐ
    expect(await backend.isRepository()).toBe(true);
    expect(await backend.isClean()).toBe(true);
    fs.rmSync(root, { recursive: true, force: true });
  });
});

function initRepoForVscode(): string {
  return initRepo();
}
