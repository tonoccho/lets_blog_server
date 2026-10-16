import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  openedExternalUrls,
  findOutputChannel,
  queueInformationResponse,
  queueWarningResponse,
  setActiveTextEditor,
  setShowTextDocumentResolver,
  setWorkspaceFolders,
  shownErrors,
  shownInformations,
  shownWarnings,
} from '../__mocks__/vscode';
import { FINDINGS_STATE_KEY } from '../rejectionEdit';
import { NOTIFIED_REJECTIONS_STATE } from '../rejectionNotifier';
import { Harness, startExtension } from './support/extensionHarness';
import { FakeEditor } from './support/fakeEditor';
import { jsonReply } from './support/localHttpServer';
import { waitFor } from './support/panelKit';

/**
 * 記事の提出(submitArticle / resubmitArticle)と、差し戻しの確認・通知・編集。
 * git は実物(一時ディレクトリの作業リポジトリと bare の origin)、外部通信(gateway)は実サーバー、
 * VS Code は代替。
 */
let harness: Harness | undefined;
const dirs: string[] = [];

function tmp(prefix: string): string {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  dirs.push(d);
  return d;
}

function git(cwd: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd, encoding: 'utf-8' }).trim();
}

interface Repo {
  root: string;
  remote: string;
  articlePath: string;
}

/** 記事ブランチ上の作業リポジトリ。origin は bare リポジトリ。 */
function makeRepo(branch = 'article/7-my-post', withRemote = true): Repo {
  const root = tmp('submit-work-');
  git(root, 'init', '-q', '-b', 'main');
  git(root, 'config', 'user.name', 't');
  git(root, 'config', 'user.email', 't@example.test');
  fs.writeFileSync(path.join(root, 'README.md'), 'hello');
  git(root, 'add', '.');
  git(root, 'commit', '-q', '-m', 'init');
  const remote = tmp('submit-remote-');
  if (withRemote) {
    git(remote, 'init', '-q', '--bare', '-b', 'main');
    git(root, 'remote', 'add', 'origin', remote);
    git(root, 'push', '-q', 'origin', 'main');
  }
  git(root, 'switch', '-q', '-c', branch);
  const articleDir = path.join(root, 'articles', 'my-post');
  fs.mkdirSync(path.join(articleDir, 'assets'), { recursive: true });
  const articlePath = path.join(articleDir, 'article.md');
  fs.writeFileSync(articlePath, TEXT);
  git(root, 'add', '.');
  git(root, 'commit', '-q', '-m', 'scaffold');
  return { root, remote, articlePath };
}

const TEXT = '---\ntitle: 私の記事\nslug: my-post\n---\n本文';

afterEach(async () => {
  await harness?.stop();
  harness = undefined;
  while (dirs.length) fs.rmSync(dirs.pop()!, { recursive: true, force: true });
});

async function start(repo: Repo, text = TEXT, options: Parameters<typeof startExtension>[0] = {}): Promise<FakeEditor> {
  harness = await startExtension(options);
  setWorkspaceFolders([{ uri: { fsPath: repo.root } }]);
  return harness.open(new FakeEditor(text, repo.articlePath))!;
}

const SUBMIT_PATH = 'POST /api/projects/7/article-review/submissions';

describe('commandSubmitArticle', () => {
  it('記事をコミットして push し、Pull Request を作って、開くかを尋ねる', async () => {
    const repo = makeRepo();
    fs.writeFileSync(repo.articlePath, `${TEXT}\n追記`);
    await start(repo, `${TEXT}\n追記`);
    harness!.api({ [SUBMIT_PATH]: jsonReply({ prNumber: 3, url: 'https://github.test/o/r/pull/3', state: 'SUBMITTED', created: true }) });
    queueInformationResponse('Pull Request を開く');

    await harness!.run('letsBlog.submitArticle');

    expect(git(repo.remote, 'branch', '--list', 'article/7-my-post')).toContain('article/7-my-post');
    expect(git(repo.root, 'log', '-1', '--format=%s')).toBe('記事を提出する: 私の記事 (#7)');
    expect(JSON.parse(harness!.requests(SUBMIT_PATH)[0].body)).toEqual({
      headBranch: 'article/7-my-post',
      githubIssueNumber: 7,
      articleSlug: 'my-post',
    });
    expect(shownInformations).toEqual(['Pull Request #3 を作成しました: https://github.test/o/r/pull/3']);
    expect(openedExternalUrls).toEqual(['https://github.test/o/r/pull/3']);
  });

  it('既存の Pull Request は再提出として知らせる(開かなければ何もしない)。resubmitArticle も同じ処理', async () => {
    const repo = makeRepo();
    await start(repo, TEXT, { projectId: null });
    harness!.api({ [SUBMIT_PATH]: jsonReply({ prNumber: 3, url: 'https://github.test/o/r/pull/3', state: 'SUBMITTED', created: false }) });

    await harness!.run('letsBlog.resubmitArticle');

    // プロジェクトは front matter に無く選択もされていないので失敗する。
    expect(shownErrors[0]).toContain('記事の提出に失敗しました');

    shownErrors.length = 0;
    harness!.open(new FakeEditor(`---\ntitle: 私の記事\nslug: my-post\nproject_id: 7\n---\n本文`, repo.articlePath));
    await harness!.run('letsBlog.resubmitArticle');
    expect(shownInformations).toEqual(['既存の Pull Request #3 を再提出しました: https://github.test/o/r/pull/3']);
    expect(openedExternalUrls).toEqual([]);
  });

  it('確認に通らない状態では、理由を示して提出しない', async () => {
    const repo = makeRepo('feature/not-article');
    await start(repo);

    await harness!.run('letsBlog.submitArticle');

    expect(shownErrors[0]).toContain('記事を提出できませんでした。現在のブランチ feature/not-article は記事用ブランチ');
    expect(harness!.requests(SUBMIT_PATH)).toHaveLength(0);
  });

  it('push に失敗したら、その理由を示す', async () => {
    const repo = makeRepo('article/7-my-post', false);
    await start(repo);

    await harness!.run('letsBlog.submitArticle');

    expect(shownErrors[0]).toContain('push に失敗しました');
  });

  it('ワークスペースが開かれていなければ、提出の失敗として知らせる', async () => {
    const repo = makeRepo();
    await start(repo);
    setWorkspaceFolders(undefined);

    await harness!.run('letsBlog.submitArticle');

    expect(shownErrors[0]).toContain('記事の提出に失敗しました');
  });
});

describe('commandCheckRejections / 差し戻しの通知と編集', () => {
  const REVIEW = {
    prNumber: 3,
    articleSlug: 'my-post',
    state: 'CHANGES_REQUESTED',
    submittedAt: '2026-10-01T00:00:00Z',
    rejectComment: '直してください',
    rejectedAt: '2026-10-02T00:00:00Z',
  };
  const MY_REVIEWS = 'GET /api/projects/7/article-review/my-reviews';

  it('未ログイン・プロジェクト未選択では、確認できない理由を案内する', async () => {
    harness = await startExtension({ login: false });
    await harness.run('letsBlog.checkRejections');
    expect(shownInformations.at(-1)).toContain('ログインしていないため確認できません');

    await harness.stop();
    harness = await startExtension({ projectId: null });
    await harness.run('letsBlog.checkRejections');
    expect(shownInformations.at(-1)).toContain('プロジェクトが未選択のため確認できません');
  });

  it('差し戻しが無ければ「ありません」と知らせ、取得に失敗したら失敗を知らせる', async () => {
    harness = await startExtension();
    harness.api({ [MY_REVIEWS]: jsonReply([{ ...REVIEW, state: 'SUBMITTED', rejectComment: null, rejectedAt: null }]) });

    await harness.run('letsBlog.checkRejections');
    expect(shownInformations).toEqual(['新たな差し戻しはありません。']);

    harness.api({ [MY_REVIEWS]: { status: 403, body: 'forbidden' } });
    await harness.run('letsBlog.checkRejections');
    expect(shownErrors[0]).toContain('差し戻しの確認に失敗しました');
  });

  it('差し戻しを通知し、「編集」で記事のブランチへ切り替えて開き、指摘事項を出力チャンネルへ表示する', async () => {
    const repo = makeRepo();
    git(repo.root, 'switch', '-q', 'main');
    const opened = new FakeEditor(TEXT, repo.articlePath);
    harness = await startExtension();
    setWorkspaceFolders([{ uri: { fsPath: repo.root } }]);
    setShowTextDocumentResolver(() => opened);
    harness.api({ [MY_REVIEWS]: jsonReply([REVIEW]) });
    queueWarningResponse('編集');

    await harness.run('letsBlog.checkRejections');
    await waitFor(() => (findOutputChannel("Let's Blog: 指摘事項")?.lines.length ?? 0) > 0);

    expect(shownWarnings[0]).toBe('記事「my-post」(PR #3)が差し戻されました。指摘事項: 直してください');
    expect(git(repo.root, 'branch', '--show-current')).toBe('article/7-my-post');
    const channel = findOutputChannel("Let's Blog: 指摘事項")!;
    expect(channel.lines).toEqual(['記事「my-post」の指摘事項:\n直してください']);
    expect(channel.shown).toBe(1);
    expect(shownInformations.at(-1)).toContain('指摘事項を「Let\'s Blog: 指摘事項」に表示しました');
    // 通知済みとして記録され、同じ差し戻しは再通知されない。
    expect(harness.context.globalState.get(NOTIFIED_REJECTIONS_STATE)).toEqual(['3|2026-10-02T00:00:00.000Z']);
    await harness.run('letsBlog.checkRejections');
    expect(shownWarnings).toHaveLength(1);
  });

  it('「再提出」を選ぶと、そのまま提出の処理へ進む', async () => {
    const repo = makeRepo();
    git(repo.root, 'switch', '-q', 'main');
    harness = await startExtension();
    setWorkspaceFolders([{ uri: { fsPath: repo.root } }]);
    const editor = new FakeEditor(`---\ntitle: 私の記事\nslug: my-post\nproject_id: 7\n---\n本文\n修正`, repo.articlePath);
    setShowTextDocumentResolver(() => editor);
    setActiveTextEditor(editor);
    harness.api({
      [MY_REVIEWS]: jsonReply([REVIEW]),
      [SUBMIT_PATH]: jsonReply({ prNumber: 3, url: 'https://github.test/o/r/pull/3', state: 'SUBMITTED', created: false }),
    });
    queueWarningResponse('編集');
    queueInformationResponse('再提出');

    await harness.run('letsBlog.checkRejections');
    await waitFor(() => harness!.requests(SUBMIT_PATH).length === 1);
    await waitFor(() => shownInformations.some((m) => m.includes('再提出しました')));

    expect(shownInformations.at(-1)).toBe('既存の Pull Request #3 を再提出しました: https://github.test/o/r/pull/3');
  });

  it('記事のブランチがこのワークスペースに無ければ、理由を警告する', async () => {
    const repo = makeRepo('article/9-other');
    harness = await startExtension();
    setWorkspaceFolders([{ uri: { fsPath: repo.root } }]);
    harness.api({ [MY_REVIEWS]: jsonReply([REVIEW]) });
    queueWarningResponse('編集');

    await harness.run('letsBlog.checkRejections');
    await waitFor(() => shownWarnings.length === 2);

    expect(shownWarnings[1]).toContain('記事「my-post」のブランチがこのワークスペースにありません');
  });

  it('ワークスペースが開かれていなければ、記事を開けなかったと知らせる', async () => {
    harness = await startExtension();
    harness.api({ [MY_REVIEWS]: jsonReply([REVIEW]) });
    queueWarningResponse('編集');

    await harness.run('letsBlog.checkRejections');
    await waitFor(() => shownErrors.length === 1);

    expect(shownErrors[0]).toContain('記事を開けませんでした');
  });

  it('通知を閉じただけなら、記事は開かない', async () => {
    const repo = makeRepo();
    harness = await startExtension();
    setWorkspaceFolders([{ uri: { fsPath: repo.root } }]);
    harness.api({ [MY_REVIEWS]: jsonReply([REVIEW]) });

    await harness.run('letsBlog.checkRejections');
    await new Promise((resolve) => setImmediate(resolve));

    expect(findOutputChannel("Let's Blog: 指摘事項")?.lines ?? []).toEqual([]);
  });

  it('起動時の確認でも、差し戻しを通知する', async () => {
    harness = await startExtension({
      loggedInAtActivation: true,
      beforeActivate: (h) => h.api({ [MY_REVIEWS]: jsonReply([REVIEW]) }),
    });
    await waitFor(() => shownWarnings.length === 1);

    expect(shownWarnings[0]).toContain('差し戻されました');
  });
});

describe('commandShowRejectionFindings', () => {
  it('保存済みの指摘事項を出力チャンネルへもう一度表示する', async () => {
    harness = await startExtension();
    await harness.context.globalState.update(FINDINGS_STATE_KEY, { 'my-post': '直してください' });
    harness.open(new FakeEditor(TEXT, '/workspace/articles/my-post/article.md'));

    await harness.run('letsBlog.showRejectionFindings');

    const channel = findOutputChannel("Let's Blog: 指摘事項")!;
    expect(channel.lines).toEqual(['記事「my-post」の指摘事項:\n直してください']);
  });

  it('指摘事項が保存されていない記事・記事でない文書・エディタが無い場合は、保存されていないと知らせる', async () => {
    harness = await startExtension();
    harness.open(new FakeEditor(TEXT, '/workspace/articles/my-post/article.md'));
    await harness.run('letsBlog.showRejectionFindings');

    harness.open(new FakeEditor(TEXT, '/workspace/notes.md'));
    await harness.run('letsBlog.showRejectionFindings');

    harness.open(undefined);
    await harness.run('letsBlog.showRejectionFindings');

    expect(shownInformations).toEqual([
      'この記事に対する指摘事項は保存されていません。',
      'この記事に対する指摘事項は保存されていません。',
      'この記事に対する指摘事項は保存されていません。',
    ]);
  });
});
