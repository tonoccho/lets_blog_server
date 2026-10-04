/** 差し戻し通知から記事を開く・再提出する(issue #1348)。git は一時ディレクトリの実リポジトリで検証する。 */

import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { Given, Then, When, World } from '../support/gherkin';
import { CliGitBackend } from '../../src/articleGit';
import { editRejectedArticle, EditResult, RejectionFindings } from '../../src/rejectionEdit';

interface EditWorld extends World {
  // articleSubmit.steps.ts の SubmitWorld と同じ名前で持ち、提出のステップを共用する。
  workDir: string;
  remoteDir?: string;
  headBeforeEdit: string;
  submissionRequests: unknown[];
  edit: {
    slug: string;
    comment: string;
    result?: EditResult;
    opened: string[];
    shown: { slug: string; comment: string }[];
    findings: RejectionFindings;
  };
}

const e = (world: World): EditWorld => world as EditWorld;

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

function setup(world: World, branch: string | undefined, withRemote: boolean): void {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'at-edit-work-'));
  git(dir, 'init', '-q', '-b', 'main');
  git(dir, 'config', 'user.name', 'at');
  git(dir, 'config', 'user.email', 'at@example.test');
  fs.writeFileSync(path.join(dir, 'README.md'), 'hello');
  git(dir, 'add', '.');
  git(dir, 'commit', '-q', '-m', 'init');
  if (withRemote) {
    const remote = fs.mkdtempSync(path.join(os.tmpdir(), 'at-edit-remote-'));
    git(remote, 'init', '-q', '--bare', '-b', 'main');
    git(dir, 'remote', 'add', 'origin', remote);
    git(dir, 'push', '-q', 'origin', 'main');
    e(world).remoteDir = remote;
  }
  if (branch) {
    git(dir, 'switch', '-q', '-c', branch);
    const articleDir = path.join(dir, 'articles', 'my-post');
    fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
    fs.writeFileSync(path.join(articleDir, 'article.md'), '# 記事');
    fs.writeFileSync(path.join(articleDir, 'assets', '.gitkeep'), '');
    git(dir, 'add', '.');
    git(dir, 'commit', '-q', '-m', 'scaffold');
    e(world).headBeforeEdit = git(dir, 'rev-parse', 'HEAD');
    if (withRemote) git(dir, 'push', '-q', 'origin', branch);
    git(dir, 'switch', '-q', 'main');
  }
  e(world).workDir = dir;
  e(world).submissionRequests = [];
}

Given('記事ブランチ {string} があり、"main" をチェックアウトしているリモート付きの作業ディレクトリがある', (world, branch) => {
  setup(world, branch, true);
});

Given('記事ブランチは無く、"main" をチェックアウトしている作業ディレクトリがある', (world) => {
  setup(world, undefined, false);
});

Given('記事 {string} が {string} と差し戻されている', (world, slug, comment) => {
  const store = new Map<string, Record<string, string>>();
  e(world).edit = {
    slug,
    comment,
    opened: [],
    shown: [],
    findings: new RejectionFindings({
      get: (key) => store.get(key),
      update: async (key, value) => void store.set(key, value),
    }),
  };
});

Given('記事を開く作業ディレクトリに未コミットの変更がある', (world) => {
  fs.writeFileSync(path.join(e(world).workDir, 'README.md'), 'dirty');
});

async function chooseEdit(world: World): Promise<void> {
  const { edit, workDir } = e(world);
  edit.result = await editRejectedArticle({
    root: workDir,
    review: { articleSlug: edit.slug, rejectComment: edit.comment },
    backend: new CliGitBackend(workDir),
    findings: edit.findings,
    openArticle: async (p) => void edit.opened.push(p),
    showFindings: (slug, comment) => void edit.shown.push({ slug, comment }),
  });
}

When('通知の「編集」を選ぶ', chooseEdit);
Given('通知の「編集」を選んでいる', chooseEdit);

Then('現在のブランチは {string} である', (world, branch) => {
  const actual = git(e(world).workDir, 'branch', '--show-current');
  if (actual !== branch) throw new Error(`現在のブランチが ${branch} ではありません: ${actual}`);
});

Then('エディタで {string} が開かれている', (world, relative) => {
  const expected = path.join(e(world).workDir, ...relative.split('/'));
  if (JSON.stringify(e(world).edit.opened) !== JSON.stringify([expected])) {
    throw new Error(`開かれたパスが想定と違います: ${e(world).edit.opened.join(', ')}`);
  }
});

Then('エディタは開かれていない', (world) => {
  if (e(world).edit.opened.length !== 0) throw new Error('エディタが開かれています');
  if (e(world).edit.shown.length !== 0) throw new Error('指摘事項が表示されています');
});

Then('記事 {string} の指摘事項として {string} が表示されている', (world, slug, comment) => {
  const shown = e(world).edit.shown;
  if (shown.length !== 1 || shown[0].slug !== slug || !shown[0].comment.includes(comment)) {
    throw new Error(`指摘事項の表示が想定と違います: ${JSON.stringify(shown)}`);
  }
});

Then('記事 {string} の指摘事項を、あとから {string} として参照できる', (world, slug, comment) => {
  if (e(world).edit.findings.get(slug) !== comment) throw new Error('指摘事項を参照できません');
});

Then('編集の結果は {string} で、理由に {string} が含まれる', (world, status, text) => {
  const r = e(world).edit.result;
  if (!r || r.status !== status || !('reason' in r) || !r.reason.includes(text)) {
    throw new Error(`編集の結果が想定と違います: ${JSON.stringify(r)}`);
  }
});
