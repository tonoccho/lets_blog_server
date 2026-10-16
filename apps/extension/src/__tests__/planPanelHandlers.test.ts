import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  lastCreatedWebviewPanel,
  openedDocuments,
  resetMocks,
  setConfiguration,
  setWorkspaceFolders,
} from '../__mocks__/vscode';
import * as api from '../apiClient';
import { PlanPanel } from '../planPanel';
import { LocalHttpServer, jsonReply, routes } from './support/localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

/**
 * PlanPanel の Webview 向けハンドラー(planPanel.test.ts は記事の雛形生成を受け持つ)。
 * 外部通信(gateway の /api/projects/*\/article-plan/*)だけを実サーバーで受け、config.ts・apiClient.ts・
 * issueParser.ts は実物を使う。
 */
let server: LocalHttpServer;
let context: TestContext;
let workspaceRoot: string;

const ISSUE = { number: 1, title: 'テストIssue', htmlUrl: 'https://example.test/issues/1', state: 'open' };
const BASE = '/api/projects/42/article-plan';

beforeEach(async () => {
  resetMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  context = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
  await loginAs(context);
  await selectProject(context, 42);
  PlanPanel.createOrShow(context);
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  await server.stop();
  if (workspaceRoot) fs.rmSync(workspaceRoot, { recursive: true, force: true });
});

describe('PlanPanel のハンドラー', () => {
  it('loadIssuesは未割り当てのIssueだけを返す', async () => {
    server.respondWith(
      routes({
        [`GET ${BASE}/issues`]: jsonReply([
          { ...ISSUE, number: 1 },
          { ...ISSUE, number: 2, assignees: ['someone'] },
        ]),
      })
    );

    await send({ command: 'loadIssues' });

    expect(payloadOf('issueList')).toEqual({ issues: [{ ...ISSUE, number: 1 }] });
  });

  it('loadCategoriesは親カテゴリ付きの一覧を返す', async () => {
    const categories = [{ name: '子', parentName: '親' }];
    server.respondWith(routes({ [`GET ${BASE}/categories/hierarchy`]: jsonReply(categories) }));

    await send({ command: 'loadCategories' });

    expect(payloadOf('categoryList')).toEqual({ categories });
  });

  it('loadIssueOutlineはIssue本文の見出しから構成案を作って返す', async () => {
    server.respondWith(
      routes({ [`GET ${BASE}/issues/1/description`]: jsonReply({ body: '## 導入\n本文\n## 手順\n本文' }) })
    );

    await send({ command: 'loadIssueOutline', issueNumber: 1 });

    expect(payloadOf('issueOutline')).toEqual({ issueNumber: 1, structure: '## 導入\n\n## 手順', headingCount: 2 });
  });

  it('sendChatは履歴・メッセージ・セッション・Issue番号を送り、返答を返す', async () => {
    server.respondWith(routes({ [`POST ${BASE}/chat`]: jsonReply({ reply: '了解', sessionId: 9 }) }));

    await send({ command: 'sendChat', history: [], message: '相談', sessionId: 9, issueNumber: 1 });

    expect(JSON.parse(server.recorded[0].body)).toMatchObject({
      history: [],
      message: '相談',
      sessionId: 9,
      githubIssueNumber: 1,
    });
    expect(payloadOf('chatResponse')).toEqual({ reply: '了解', sessionId: 9 });
  });

  it('suggestStructureは構成案を返す', async () => {
    server.respondWith(routes({ [`POST ${BASE}/suggest-structure`]: jsonReply({ structure: '## 見出し' }) }));

    await send({ command: 'suggestStructure', history: [] });

    expect(payloadOf('structureSuggestion')).toEqual({ structure: '## 見出し' });
  });

  it('acceptStructureは構成案をIssueへ反映し、結果を返す', async () => {
    server.respondWith(
      routes({ [`POST ${BASE}/issues/1/accept-structure`]: jsonReply({ issueNumber: 1, issueUrl: 'https://example.test/issues/1' }) })
    );

    await send({ command: 'acceptStructure', issueNumber: 1, structure: '## 見出し' });

    expect(JSON.parse(server.recorded[0].body)).toEqual({ structure: '## 見出し' });
    expect(payloadOf('structureAccepted')).toEqual({ issueNumber: 1, issueUrl: 'https://example.test/issues/1' });
  });

  it('suggestMetadataはメタデータの提案を返す', async () => {
    const suggestion = { titles: ['題'], slugs: ['slug'], categories: ['c'], tags: ['t'] };
    server.respondWith(routes({ [`POST ${BASE}/suggest-metadata`]: jsonReply(suggestion) }));

    await send({ command: 'suggestMetadata', history: [] });

    expect(payloadOf('metadataSuggestion')).toEqual(suggestion);
  });

  it('プロジェクトが未選択なら、選択を促すエラーを返し、サーバーへは送らない', async () => {
    context.stateValues.clear();

    await send({ command: 'loadIssues' });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('Select Project') });
  });

  it('cancelは応答待ちの相談を中断し、キャンセルとして通知する', async () => {
    server.respondWith({ status: 200, hang: true });
    void send({ command: 'sendChat', history: [], message: '相談' }, false);
    await waitFor(() => server.recorded.length === 1);

    await send({ command: 'cancel' });

    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });

  it('openArticleは、雛形を生成する前なら何も開かない', async () => {
    await send({ command: 'openArticle' }, false);

    expect(openedDocuments).toEqual([]);
  });
});

describe('PlanPanel: 雛形の生成後のIssue割り当て', () => {
  const METADATA = { title: 'タイトル', slug: 'my-article-01', categories: [], tags: [] };

  function git(...args: string[]): void {
    execFileSync('git', args, { cwd: workspaceRoot });
  }

  beforeEach(() => {
    workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-planpanel-assign-'));
    git('init', '-q', '-b', 'main');
    git('config', 'user.name', 'tester');
    git('config', 'user.email', 'tester@example.test');
    fs.writeFileSync(path.join(workspaceRoot, 'README.md'), 'hello');
    git('add', '.');
    git('commit', '-q', '-m', 'init');
    setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  });

  it('割り当てに失敗しても記事は生成済みとして伝え、記事を開く。openArticleでもう一度開ける', async () => {
    server.respondWith(
      routes({
        [`GET ${BASE}/issues/1/description`]: jsonReply({ body: '本文' }),
        [`POST ${BASE}/issues/1/assign`]: { status: 403, body: 'forbidden' },
      })
    );

    await send({ command: 'approveAndScaffold', issue: ISSUE, metadata: METADATA });

    const articlePath = path.join(workspaceRoot, 'articles', 'my-article-01', 'article.md');
    expect(fs.existsSync(articlePath)).toBe(true);
    expect(payloadOf('error')).toEqual({
      error: expect.stringContaining('記事ファイルは生成されましたが、issueの割り当てに失敗しました'),
    });
    expect(openedDocuments).toEqual([articlePath, articlePath]);

    openedDocuments.length = 0;
    await send({ command: 'openArticle' }, false);
    expect(openedDocuments).toEqual([articlePath, articlePath]);
  });
});
