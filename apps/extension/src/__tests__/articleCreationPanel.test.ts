import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  lastCreatedWebviewPanel,
  openedDocuments,
  resetMocks,
  setConfiguration,
  setWarningResponse,
  setWorkspaceFolders,
  shownInformations,
} from '../__mocks__/vscode';
import * as api from '../apiClient';
import { ArticleCreationPanel } from '../articleCreationPanel';
import { getProjectId } from '../config';
import { parseArticle } from '../frontMatter';
import { LocalHttpServer, jsonReply, routes } from './support/localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './support/extensionContext';
import { payloadOf, posted, send, waitFor } from './support/panelKit';

// 外部通信(gateway の /api)だけを実サーバーで受ける。config.ts・apiClient.ts・articleScaffold.ts は実物で、
// 記事は一時ディレクトリへ実際に書き出す。
let server: LocalHttpServer;
let context: TestContext;
let workspaceRoot: string;

const METADATA = { projectId: 5, title: 'タイトル', slug: 'my-article', categories: ['tech'], tags: ['a'], status: 'draft' };

beforeEach(async () => {
  resetMocks();
  api.clearResponseCache();
  workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-articlecreation-'));
  setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  context = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
  await loginAs(context);
  ArticleCreationPanel.createOrShow(context);
});

afterEach(async () => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(workspaceRoot, { recursive: true, force: true });
  await server.stop();
});

describe('ArticleCreationPanel: 一覧の読み込み', () => {
  it('loadProjectsはプロジェクト一覧と、直前に選んだプロジェクトを返す', async () => {
    await selectProject(context, 5);
    server.respondWith(routes({ 'GET /api/projects': jsonReply([{ id: 5, name: 'P', slug: 'p' }]) }));

    await send({ command: 'loadProjects' });

    expect(payloadOf('projectList')).toEqual({
      projects: [{ id: 5, name: 'P', slug: 'p' }],
      selectedProjectId: 5,
    });
  });

  it('プロジェクトを選んだことが無ければ、選択済みはnullで返す', async () => {
    server.respondWith(routes({ 'GET /api/projects': jsonReply([]) }));

    await send({ command: 'loadProjects' });

    expect(payloadOf('projectList')).toEqual({ projects: [], selectedProjectId: null });
  });

  it('loadPostStatusesはサーバーの投稿ステータス一覧を返す', async () => {
    const statuses = [{ value: 'draft', label: '下書き' }, { value: 'future', label: '予約' }];
    server.respondWith(routes({ 'GET /api/metadata/post-statuses': jsonReply(statuses) }));

    await send({ command: 'loadPostStatuses' });

    expect(payloadOf('postStatusList')).toEqual({ statuses });
  });

  it('投稿ステータス一覧を取得できなければ、draft/publishだけの一覧に縮退する', async () => {
    server.respondWith({ status: 403, body: 'forbidden' });

    await send({ command: 'loadPostStatuses' });

    expect(payloadOf('postStatusList')).toEqual({
      statuses: [
        { value: 'draft', label: '下書き (draft)' },
        { value: 'publish', label: '公開 (publish)' },
      ],
    });
  });

  it('loadCategoriesは親カテゴリ付きの一覧を返す', async () => {
    const categories = [{ name: '子', parentName: '親' }];
    server.respondWith(routes({ 'GET /api/projects/5/article-plan/categories/hierarchy': jsonReply(categories) }));

    await send({ command: 'loadCategories', projectId: 5 });

    expect(payloadOf('categoryList')).toEqual({ categories });
  });

  it('カテゴリを取得できなければ、記事作成は続けられるよう空の一覧を返す', async () => {
    server.respondWith({ status: 403, body: 'forbidden' });

    await send({ command: 'loadCategories', projectId: 5 });

    expect(payloadOf('categoryList')).toEqual({ categories: [] });
  });

  it('ログインユーザーが分からないloadCategoriesはエラーを返し、カテゴリは取りに行かない', async () => {
    const noActor = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
    await loginAs(noActor, null);
    lastCreatedWebviewPanel?.fireDispose();
    ArticleCreationPanel.createOrShow(noActor);

    await send({ command: 'loadCategories', projectId: 5 });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('Login') });
  });
});

describe('ArticleCreationPanel: AIへの相談', () => {
  it('sendChatは壁打ちの履歴とメッセージをサーバーへ送り、返答をWebviewへ返す', async () => {
    server.respondWith(routes({ 'POST /api/projects/5/article-plan/chat': jsonReply({ reply: 'こんにちは', sessionId: 3 }) }));

    await send({ command: 'sendChat', projectId: 5, history: [], message: '相談', sessionId: 3 });

    expect(JSON.parse(server.recorded[0].body)).toMatchObject({ message: '相談', sessionId: 3, history: [] });
    expect(payloadOf('chatResponse')).toEqual({ reply: 'こんにちは', sessionId: 3 });
  });

  it('suggestMetadataはメタデータの提案を返す', async () => {
    const suggestion = { titles: ['題'], slugs: ['slug'], categories: ['c'], tags: ['t'] };
    server.respondWith(routes({ 'POST /api/projects/5/article-plan/suggest-metadata': jsonReply(suggestion) }));

    await send({ command: 'suggestMetadata', projectId: 5, history: [] });

    expect(payloadOf('metadataSuggestion')).toEqual(suggestion);
  });

  it('suggestStructureは構成案を返す', async () => {
    server.respondWith(routes({ 'POST /api/projects/5/article-plan/suggest-structure': jsonReply({ structure: '## 見出し' }) }));

    await send({ command: 'suggestStructure', projectId: 5, history: [] });

    expect(payloadOf('structureSuggestion')).toEqual({ structure: '## 見出し' });
  });

  it('ログインユーザーが分からない相談はエラーを返し、サーバーへは送らない', async () => {
    const noActor = createContext(`file://${path.resolve(__dirname, '..', '..')}`);
    await loginAs(noActor, null);
    lastCreatedWebviewPanel?.fireDispose();
    ArticleCreationPanel.createOrShow(noActor);

    await send({ command: 'sendChat', projectId: 5, history: [], message: '相談' });

    expect(server.recorded).toHaveLength(0);
    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('Login') });
  });

  it('cancelは応答待ちの相談を中断し、キャンセルとして通知する', async () => {
    server.respondWith({ status: 200, hang: true });
    void send({ command: 'sendChat', projectId: 5, history: [], message: '相談' }, false);
    await waitFor(() => server.recorded.length === 1);

    await send({ command: 'cancel' });

    expect(posted().some((m) => m.command === 'cancelled')).toBe(true);
  });
});

describe('ArticleCreationPanel: 記事の作成', () => {
  it('createArticleは記事を生成して既定のプロジェクトを更新し、開いて通知する', async () => {
    await send({ command: 'createArticle', metadata: METADATA, content: '# 本文' });

    const articlePath = path.join(workspaceRoot, 'articles', 'my-article', 'article.md');
    const parsed = parseArticle(fs.readFileSync(articlePath, 'utf-8'));
    expect(parsed.data).toMatchObject({ title: 'タイトル', slug: 'my-article', status: 'draft', categories: ['tech'], tags: ['a'] });
    expect(parsed.content.trim()).toBe('# 本文');
    expect(getProjectId(context)).toBe(5);
    expect(payloadOf('articleCreated')).toEqual({ slug: 'my-article' });
    expect(openedDocuments).toEqual([articlePath, articlePath]);
    expect(shownInformations).toEqual(['articles/my-article/article.md を作成しました。']);
  });

  it('本文を渡さなければ、案内文入りの記事を生成する', async () => {
    await send({ command: 'createArticle', metadata: METADATA });

    const articlePath = path.join(workspaceRoot, 'articles', 'my-article', 'article.md');
    expect(parseArticle(fs.readFileSync(articlePath, 'utf-8')).content).toContain('記事本文をここに記入してください。');
  });

  it('上書きを拒否されたらキャンセルとして伝え、プロジェクトは更新しない', async () => {
    fs.mkdirSync(path.join(workspaceRoot, 'articles', 'my-article'), { recursive: true });
    setWarningResponse('No');

    await send({ command: 'createArticle', metadata: METADATA });

    expect(payloadOf('error')).toEqual({ error: 'キャンセルしました。' });
    expect(getProjectId(context)).toBeUndefined();
  });

  it('ワークスペースが開かれていなければエラーを返す', async () => {
    setWorkspaceFolders(undefined);

    await send({ command: 'createArticle', metadata: METADATA });

    expect(payloadOf('error')).toEqual({ error: expect.stringContaining('ワークスペースフォルダが開かれていません') });
  });

  it('closeはパネルを閉じる', async () => {
    const panel = lastCreatedWebviewPanel;

    await send({ command: 'close' });

    expect(panel?.disposed).toBe(true);
  });
});
