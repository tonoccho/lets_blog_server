/** Issue起点の記事作成のブランチ・コミット(issue #1335)。一時ディレクトリの実gitリポジトリに対して呼ぶ。 */

import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { Given, Then, When, World } from '../support/gherkin';
import { CliGitBackend, scaffoldArticleOnBranch, ScaffoldOnBranchResult } from '../../src/articleGit';

interface GitWorld extends World {
  workDir: string;
  branchResult?: ScaffoldOnBranchResult<{ articleDir: string; articlePath: string }>;
  branchError?: unknown;
}

const g = (world: World): GitWorld => world as GitWorld;

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

Given('gitリポジトリである作業ディレクトリがある', (world) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'at-article-git-'));
  git(dir, 'init', '-q', '-b', 'main');
  git(dir, 'config', 'user.name', 'at');
  git(dir, 'config', 'user.email', 'at@example.test');
  fs.writeFileSync(path.join(dir, 'README.md'), 'hello');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'init');
  g(world).workDir = dir;
});

Given('gitリポジトリではない作業ディレクトリがある', (world) => {
  g(world).workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'at-article-nogit-'));
});

Given('作業ディレクトリに未コミットの変更がある', (world) => {
  fs.writeFileSync(path.join(g(world).workDir, 'README.md'), 'dirty');
});

Given('ブランチ {string} が既に存在する', (world, name) => {
  git(g(world).workDir, 'branch', name);
});

type ScaffoldFiles = { articleDir: string; articlePath: string };

function writeScaffold(root: string, slug: string): ScaffoldFiles {
  const articleDir = path.join(root, 'articles', slug);
  fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
  fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');
  const articlePath = path.join(articleDir, 'article.md');
  fs.writeFileSync(articlePath, '# 記事');
  return { articleDir, articlePath };
}

async function scaffold(
  world: World,
  issueNumber: number,
  slug: string,
  choice: 'switch' | 'abort',
  generate: (root: string) => Promise<ScaffoldFiles | undefined> = async (root) => writeScaffold(root, slug)
) {
  const root = g(world).workDir;
  try {
    g(world).branchResult = await scaffoldArticleOnBranch({
      root,
      backend: new CliGitBackend(root),
      issueNumber,
      slug,
      title: '受け入れテストの記事',
      scaffold: () => generate(root),
      chooseOnExistingBranch: async () => choice,
    });
  } catch (error) {
    g(world).branchError = error;
  }
}

Given('ブランチ {string} に同じ雛形がコミット済みである', (world, name) => {
  const dir = g(world).workDir;
  git(dir, 'switch', '-q', '-c', name);
  writeScaffold(dir, 'my-post');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'scaffold');
  git(dir, 'switch', '-q', 'main');
});

When('上書きを拒否して Issue {int} のスラッグ {string} で記事の雛形を作る', async (world, n, slug) => {
  await scaffold(world, Number(n), slug, 'abort', async () => undefined);
});

When('雛形生成が失敗する状況で Issue {int} のスラッグ {string} で記事の雛形を作る', async (world, n, slug) => {
  await scaffold(world, Number(n), slug, 'abort', async () => {
    throw new Error('雛形生成の失敗');
  });
});

When('同名ブランチへ切り替えを選んで Issue {int} のスラッグ {string} で記事の雛形を作る', async (world, n, slug) => {
  await scaffold(world, Number(n), slug, 'switch');
});

Then('雛形生成は取り消しとして扱われる', (world) => {
  if (g(world).branchResult?.status !== 'cancelled') {
    throw new Error(`cancelled ではありません: ${JSON.stringify(g(world).branchResult)}`);
  }
});

Then('雛形生成の失敗がそのまま報告される', (world) => {
  if (!String(g(world).branchError).includes('雛形生成の失敗')) {
    throw new Error(`元の失敗が報告されていません: ${String(g(world).branchError)}`);
  }
});

Then('新しいコミットを作らずに作成済みとして完了する', (world) => {
  const r = g(world).branchResult;
  if (g(world).branchError || r?.status !== 'created' || r.committed !== false) {
    throw new Error(`コミットなしの完了ではありません: ${JSON.stringify(r)} ${String(g(world).branchError)}`);
  }
});

When('Issue {int} のスラッグ {string} で記事の雛形を作る', async (world, n, slug) => {
  await scaffold(world, Number(n), slug, 'abort');
});

When('同名ブランチでは中断を選んで Issue {int} のスラッグ {string} で記事の雛形を作る', async (world, n, slug) => {
  await scaffold(world, Number(n), slug, 'abort');
});

Then('ブランチ {string} がチェックアウトされている', (world, name) => {
  if (git(g(world).workDir, 'branch', '--show-current') !== name) {
    throw new Error(`チェックアウト中のブランチが ${name} ではありません: ${JSON.stringify(g(world).branchResult)}`);
  }
});

Then('雛形の article.md と assets/.gitkeep がコミット済みで未コミットの変更が無い', (world) => {
  const dir = g(world).workDir;
  const tracked = git(dir, 'ls-files').split('\n');
  for (const f of ['articles/my-post/article.md', 'articles/my-post/assets/.gitkeep']) {
    if (!tracked.includes(f)) throw new Error(`${f} がコミットされていません`);
  }
  if (git(dir, 'status', '--porcelain') !== '') throw new Error('未コミットの変更が残っています');
});

Then('中断の理由に {string} が含まれる', (world, text) => {
  const r = g(world).branchResult;
  if (r?.status !== 'aborted' || !r.reason.includes(text)) {
    throw new Error(`中断の理由に ${text} が含まれません: ${JSON.stringify(r)}`);
  }
});

Then('articles ディレクトリは作られていない', (world) => {
  if (fs.existsSync(path.join(g(world).workDir, 'articles'))) throw new Error('articles/ が作られています');
});

Then('記事用ブランチは作られていない', (world) => {
  if (git(g(world).workDir, 'branch', '--list', 'article/*') !== '') throw new Error('記事用ブランチが作られています');
});

Then('現在のブランチは {string} のままである', (world, name) => {
  if (git(g(world).workDir, 'branch', '--show-current') !== name) throw new Error('ブランチが変化しています');
});
