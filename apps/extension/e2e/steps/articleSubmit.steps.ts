/** 記事の提出(コミット・push・PR作成、issue #1342)。push は一時ディレクトリの bare リポジトリで検証する。 */

import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { Given, Then, When, World } from '../support/gherkin';
import { CliGitBackend } from '../../src/articleGit';
import { submitArticle, SubmissionRequest, SubmitResult } from '../../src/articleSubmit';
import * as apiClient from '../../src/apiClient';
import { createGithubLinkedProject, openPullRequestUrlsFor, pushedBranchOnGithubStub } from '../support/api';
import { w } from './common.steps';

interface SubmitWorld extends World {
  workDir: string;
  remoteDir?: string;
  branch: string;
  submitResult: SubmitResult;
  submissionRequests: SubmissionRequest[];
  headBeforeEdit: string;
  submission?: { prNumber: number; url: string; created: boolean };
  projectId?: number;
  stubHead?: string;
}

const s = (world: World): SubmitWorld => world as SubmitWorld;

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

function setup(world: World, branch: string, withRemote: boolean): void {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'at-submit-work-'));
  git(dir, 'init', '-q', '-b', 'main');
  git(dir, 'config', 'user.name', 'at');
  git(dir, 'config', 'user.email', 'at@example.test');
  fs.writeFileSync(path.join(dir, 'README.md'), 'hello');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'init');
  if (withRemote) {
    const remote = fs.mkdtempSync(path.join(os.tmpdir(), 'at-submit-remote-'));
    git(remote, 'init', '-q', '--bare', '-b', 'main');
    git(dir, 'remote', 'add', 'origin', remote);
    git(dir, 'push', '-q', 'origin', 'main');
    s(world).remoteDir = remote;
  }
  git(dir, 'switch', '-q', '-c', branch);
  const articleDir = path.join(dir, 'articles', 'my-post');
  fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
  fs.writeFileSync(path.join(articleDir, 'article.md'), '# 記事');
  fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'scaffold');
  s(world).workDir = dir;
  s(world).branch = branch;
  s(world).headBeforeEdit = git(dir, 'rev-parse', 'HEAD');
  s(world).submissionRequests = [];
}

Given('記事ブランチ {string} にいて、リモートの bare リポジトリがある作業ディレクトリがある', (world, branch) => {
  setup(world, branch, true);
});

Given('記事ブランチ {string} にいて、リモートが無い作業ディレクトリがある', (world, branch) => {
  setup(world, branch, false);
});

Given('記事 {string} のファイルを編集している', (world, slug) => {
  const dir = path.join(s(world).workDir, 'articles', slug);
  fs.appendFileSync(path.join(dir, 'article.md'), '\n編集した本文');
  fs.writeFileSync(path.join(dir, 'assets', 'new.png'), 'x');
});

Given('記事ディレクトリの外にも未コミットの変更がある', (world) => {
  fs.writeFileSync(path.join(s(world).workDir, 'README.md'), 'dirty');
  fs.writeFileSync(path.join(s(world).workDir, 'untracked.txt'), 'u');
});

async function submit(world: World, slug: string, frontMatter: string): Promise<void> {
  const root = s(world).workDir;
  const text = `---\n${frontMatter}---\n本文`;
  s(world).submitResult = await submitArticle({
    root,
    articlePath: path.join(root, 'articles', slug, 'article.md'),
    text,
    backend: new CliGitBackend(root),
    // このシナリオ群が見るのは git の振る舞い。PR 作成の依頼が行われたかだけを記録する。
    createSubmission: async (request) => {
      s(world).submissionRequests.push(request);
      return { prNumber: 1, url: 'https://github.test/pull/1', created: true };
    },
  });
}

When('記事 {string} を提出する', async (world, slug) => {
  await submit(world, slug, `title: 私の記事\nslug: ${slug}\n`);
});

When('front matter に title が無い記事 {string} を提出する', async (world, slug) => {
  await submit(world, slug, `slug: ${slug}\n`);
});

When('front matter に slug が無い記事 {string} を提出する', async (world, slug) => {
  await submit(world, slug, 'title: 私の記事\n');
});

Then('提出の結果は {string} である', (world, status) => {
  const r = s(world).submitResult;
  if (r.status !== status) throw new Error(`提出の結果が ${status} ではありません: ${JSON.stringify(r)}`);
});

Then('提出の結果は {string} で、理由に {string} が含まれる', (world, status, text) => {
  const r = s(world).submitResult;
  if (r.status !== status || !('reason' in r) || !r.reason.includes(text)) {
    throw new Error(`提出の結果が想定と違います: ${JSON.stringify(r)}`);
  }
});

Then('リモートの {string} に編集した記事のコミットが届いている', (world, branch) => {
  const remoteHead = git(s(world).remoteDir!, 'rev-parse', branch);
  if (remoteHead !== git(s(world).workDir, 'rev-parse', 'HEAD') || remoteHead === s(world).headBeforeEdit) {
    throw new Error('リモートに編集のコミットが届いていません');
  }
  const body = git(s(world).remoteDir!, 'show', `${branch}:articles/my-post/article.md`);
  if (!body.includes('編集した本文')) throw new Error('リモートの記事に編集が含まれていません');
});

Then('最新のコミットに含まれるのは記事ディレクトリのファイルだけである', (world) => {
  const files = git(s(world).workDir, 'show', '--name-only', '--format=', 'HEAD').split('\n');
  if (files.length === 0 || files.some((f) => !f.startsWith('articles/my-post/'))) {
    throw new Error(`記事ディレクトリ以外が含まれています: ${files.join(', ')}`);
  }
});

Then('記事ディレクトリの外の変更は未コミットのまま残っている', (world) => {
  const status = execFileSync('git', ['status', '--porcelain'], { cwd: s(world).workDir, encoding: 'utf-8' }).trimEnd().split('\n').sort();
  if (JSON.stringify(status) !== JSON.stringify([' M README.md', '?? untracked.txt'])) {
    throw new Error(`ワークツリーの状態が想定と違います: ${status.join(' | ')}`);
  }
});

Then('PR 作成の依頼が1回行われている', (world) => {
  if (s(world).submissionRequests.length !== 1) throw new Error('PR 作成の依頼が1回ではありません');
});

Then('PR 作成の依頼は行われていない', (world) => {
  if (s(world).submissionRequests.length !== 0) throw new Error('PR 作成の依頼が行われています');
});

Then('リモートに記事ブランチは届いていない', (world) => {
  if (git(s(world).remoteDir!, 'branch', '--list', 'article/*') !== '') throw new Error('記事ブランチが届いています');
});

Then('編集は未コミットのまま残っている', (world) => {
  if (git(s(world).workDir, 'rev-parse', 'HEAD') !== s(world).headBeforeEdit) throw new Error('コミットが作られています');
  if (!git(s(world).workDir, 'status', '--porcelain').includes('articles/my-post')) {
    throw new Error('編集が未コミットで残っていません');
  }
});

// ---- PR 作成(サーバ API。git は関与しない) ----

Given('GitHub 連携が設定されたプロジェクトで、push 済みのブランチがスタブにある', async (world) => {
  const project = await createGithubLinkedProject(w(world).token);
  s(world).projectId = project.id;
  const head = `article/9${Date.now() % 100000}-submit-at`;
  s(world).stubHead = head;
  await pushedBranchOnGithubStub(head);
});

When('そのブランチを拡張の提出 API 呼び出しで提出する', async (world) => {
  const head = s(world).stubHead!;
  s(world).submission = await apiClient.submitArticleReview(w(world).token, w(world).actor, s(world).projectId!, {
    headBranch: head,
    githubIssueNumber: Number(/^article\/(\d+)-/.exec(head)![1]),
    articleSlug: 'submit-at',
  });
});

Then('PR の URL が返り、スタブにそのブランチの開いている PR が作成されている', async (world) => {
  const result = s(world).submission;
  if (!result || !/^https?:\/\//.test(result.url) || !result.created) {
    throw new Error(`PR の URL が返っていません: ${JSON.stringify(result)}`);
  }
  const urls = await openPullRequestUrlsFor(s(world).stubHead!);
  if (urls.length !== 1) throw new Error(`スタブの開いている PR が1件ではありません: ${urls.join(', ')}`);
});
