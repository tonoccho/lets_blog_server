import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { CliGitBackend, GitBackend } from '../articleGit';
import { buildSubmitCommitMessage, parseArticleBranch, submitArticle, SubmissionRequest } from '../articleSubmit';

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

const dirs: string[] = [];
afterEach(() => {
  while (dirs.length) fs.rmSync(dirs.pop()!, { recursive: true, force: true });
});

function tmp(prefix: string): string {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  dirs.push(d);
  return d;
}

interface Fixture {
  root: string;
  remote?: string;
  articlePath: string;
}

/** 記事ブランチ上の作業リポジトリ。withRemote なら bare リポジトリを origin に持つ。 */
function fixture(options: { withRemote: boolean; branch?: string } = { withRemote: true }): Fixture {
  const root = tmp('submit-work-');
  git(root, 'init', '-q', '-b', 'main');
  git(root, 'config', 'user.name', 't');
  git(root, 'config', 'user.email', 't@example.test');
  fs.writeFileSync(path.join(root, 'README.md'), 'hello');
  git(root, 'add', '.');
  git(root, 'commit', '-q', '-m', 'init');
  let remote: string | undefined;
  if (options.withRemote) {
    remote = tmp('submit-remote-');
    git(remote, 'init', '-q', '--bare', '-b', 'main');
    git(root, 'remote', 'add', 'origin', remote);
    git(root, 'push', '-q', 'origin', 'main');
  }
  git(root, 'switch', '-q', '-c', options.branch ?? 'article/7-my-post');
  const articleDir = path.join(root, 'articles', 'my-post');
  fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
  const articlePath = path.join(articleDir, 'article.md');
  fs.writeFileSync(articlePath, '# 記事');
  fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');
  git(root, 'add', '.');
  git(root, 'commit', '-q', '-m', 'scaffold');
  return { root, remote, articlePath };
}

const TEXT = '---\ntitle: 私の記事\nslug: my-post\n---\n本文';

function createSubmission(): jest.Mock<Promise<{ prNumber: number; url: string; created: boolean }>, [SubmissionRequest]> {
  return jest.fn(async (_request: SubmissionRequest) => ({ prNumber: 3, url: 'https://github.test/o/r/pull/3', created: true }));
}

describe('parseArticleBranch / buildSubmitCommitMessage', () => {
  it('article/<番号>-<スラッグ> からIssue番号とスラッグを取り出す', () => {
    expect(parseArticleBranch('article/12-my-slug')).toEqual({ issueNumber: 12, slug: 'my-slug' });
  });

  it.each(['main', 'article/x-slug', 'article/12-', 'feature/12-a', 'article/0-a'])('記事ブランチでなければ undefined (%s)', (b) => {
    expect(parseArticleBranch(b)).toBeUndefined();
  });

  it('コミットメッセージにタイトルとIssue番号を含める', () => {
    const m = buildSubmitCommitMessage('題', 5);
    expect(m).toContain('題');
    expect(m).toContain('#5');
  });
});

describe('submitArticle', () => {
  it('記事ディレクトリの変更だけをコミットして push し、PR作成APIを呼ぶ', async () => {
    const f = fixture();
    fs.appendFileSync(f.articlePath, '\n追記');
    fs.writeFileSync(path.join(f.root, 'articles', 'my-post', 'assets', 'new.png'), 'x');
    fs.writeFileSync(path.join(f.root, 'README.md'), 'dirty'); // 記事外の変更
    fs.writeFileSync(path.join(f.root, 'untracked.txt'), 'u');
    const create = createSubmission();

    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });

    expect(result).toEqual({
      status: 'submitted',
      branch: 'article/7-my-post',
      committed: true,
      prNumber: 3,
      url: 'https://github.test/o/r/pull/3',
      created: true,
    });
    expect(create).toHaveBeenCalledWith({ headBranch: 'article/7-my-post', githubIssueNumber: 7, articleSlug: 'my-post' });
    expect(git(f.root, 'show', '--name-only', '--format=%s', 'HEAD').split('\n')).toEqual([
      buildSubmitCommitMessage('私の記事', 7),
      '',
      'articles/my-post/article.md',
      'articles/my-post/assets/new.png',
    ]);
    // 記事外の変更はコミットされずワークツリーに残る
    expect(execFileSync('git', ['status', '--porcelain'], { cwd: f.root, encoding: 'utf-8' }).trimEnd().split('\n').sort()).toEqual([' M README.md', '?? untracked.txt']);
    // リモートへ届き upstream が設定された
    expect(git(f.remote!, 'rev-parse', 'article/7-my-post')).toBe(git(f.root, 'rev-parse', 'HEAD'));
    expect(git(f.root, 'rev-parse', '--abbrev-ref', '@{upstream}')).toBe('origin/article/7-my-post');
  });

  it('記事ディレクトリに未コミットの変更が無くても、push してPR作成まで進む', async () => {
    const f = fixture();
    const create = createSubmission();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });
    expect(result).toMatchObject({ status: 'submitted', committed: false });
    expect(git(f.remote!, 'rev-parse', 'article/7-my-post')).toBe(git(f.root, 'rev-parse', 'HEAD'));
    expect(create).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['title', '---\nslug: my-post\n---\n本文'],
    ['slug', '---\ntitle: 題\n---\n本文'],
    ['title', '---\ntitle: "  "\nslug: my-post\n---\n本文'],
    ['slug', '本文だけでfront matterが無い'],
  ])('front matter に %s が無ければ理由を示して中断し、コミットも push もしない', async (missing, text) => {
    const f = fixture();
    fs.appendFileSync(f.articlePath, '\n追記');
    const head = git(f.root, 'rev-parse', 'HEAD');
    const create = createSubmission();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining(missing === 'title' ? 'title' : 'slug') });
    expect(git(f.root, 'rev-parse', 'HEAD')).toBe(head);
    expect(git(f.remote!, 'branch', '--list', 'article/*')).toBe('');
    expect(create).not.toHaveBeenCalled();
  });

  it('push が失敗したら理由を返し、PR作成APIを呼ばない', async () => {
    const f = fixture({ withRemote: false });
    const create = createSubmission();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });
    expect(result).toEqual({ status: 'push-failed', reason: expect.stringContaining('push') });
    expect(create).not.toHaveBeenCalled();
  });

  it('PR作成APIが失敗したら push 済みであることがわかる理由を返す', async () => {
    const f = fixture();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: async () => {
        throw new Error('サーバーが拒否しました');
      },
    });
    expect(result).toEqual({ status: 'submission-failed', reason: expect.stringContaining('サーバーが拒否しました') });
    expect(git(f.remote!, 'rev-parse', 'article/7-my-post')).toBe(git(f.root, 'rev-parse', 'HEAD'));
  });

  it('PR作成APIがError以外を投げても理由を文字列で返す', async () => {
    const f = fixture();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: () => Promise.reject('boom'),
    });
    expect(result).toEqual({ status: 'submission-failed', reason: expect.stringContaining('boom') });
  });

  it('記事ブランチでなければ中断する', async () => {
    const f = fixture({ withRemote: true, branch: 'feature/x' });
    const create = createSubmission();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('article/') });
    expect(create).not.toHaveBeenCalled();
  });

  it('ブランチを持たない(detached HEAD)状態では中断する', async () => {
    const f = fixture();
    git(f.root, 'checkout', '-q', '--detach');
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: createSubmission(),
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('(なし)') });
  });

  it('gitリポジトリでなければ中断する', async () => {
    const root = tmp('submit-nogit-');
    const result = await submitArticle({
      root,
      articlePath: path.join(root, 'articles', 'my-post', 'article.md'),
      text: TEXT,
      backend: new CliGitBackend(root),
      createSubmission: createSubmission(),
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('git') });
  });

  it('articles/<slug>/article.md 以外のファイルでは中断する', async () => {
    const f = fixture();
    const result = await submitArticle({
      root: f.root,
      articlePath: path.join(f.root, 'README.md'),
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: createSubmission(),
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('articles/') });
  });

  it('記事ディレクトリ外にステージ済みの変更があれば、巻き込まないよう中断する', async () => {
    const f = fixture();
    fs.writeFileSync(path.join(f.root, 'README.md'), 'staged');
    git(f.root, 'add', 'README.md');
    const head = git(f.root, 'rev-parse', 'HEAD');
    const create = createSubmission();
    const result = await submitArticle({
      root: f.root,
      articlePath: f.articlePath,
      text: TEXT,
      backend: new CliGitBackend(f.root),
      createSubmission: create,
    });
    expect(result).toEqual({ status: 'aborted', reason: expect.stringContaining('ステージ') });
    expect(git(f.root, 'rev-parse', 'HEAD')).toBe(head);
    expect(create).not.toHaveBeenCalled();
  });

  it('バックエンドを差し替えても同じ手順(commit → push → API)で呼ぶ', async () => {
    const calls: string[] = [];
    const backend: GitBackend = {
      isRepository: async () => true,
      isClean: async () => true,
      resolveBaseRef: async () => 'main',
      branchExists: async () => false,
      createBranch: async () => undefined,
      checkout: async () => undefined,
      currentBranch: async () => 'article/9-my-post',
      hasChangesIn: async () => true,
      hasStagedOutside: async () => false,
      commit: async (paths, message) => void calls.push(`commit ${paths.join(',')} ${message}`),
      push: async (branch) => void calls.push(`push ${branch}`),
    };
    const result = await submitArticle({
      root: '/r',
      articlePath: path.join('/r', 'articles', 'my-post', 'article.md'),
      text: TEXT,
      backend,
      createSubmission: async (req) => {
        calls.push(`api ${req.githubIssueNumber}`);
        return { prNumber: 1, url: 'u', created: false };
      },
    });
    expect(result).toMatchObject({ status: 'submitted', created: false });
    expect(calls).toEqual([`commit articles/my-post ${buildSubmitCommitMessage('私の記事', 9)}`, 'push article/9-my-post', 'api 9']);
  });
});
