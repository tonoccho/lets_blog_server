import { CompletionItemKind, resetMocks, setConfiguration } from '../__mocks__/vscode';
import * as api from '../apiClient';
import { FrontMatterCompletionProvider } from '../frontMatterCompletionProvider';
import { LocalHttpServer, jsonReply, routes } from './support/localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './support/extensionContext';

// 外部通信(gateway の /api)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let context: TestContext;
let provider: FrontMatterCompletionProvider;

type Item = { label: string; kind?: number; detail?: string; range?: unknown };

function doc(text: string, languageId = 'markdown') {
  return { languageId, getText: () => text };
}

async function complete(text: string, line: number, character: number, languageId = 'markdown'): Promise<Item[] | undefined> {
  const items = await provider.provideCompletionItems(
    doc(text, languageId) as never,
    { line, character } as never
  );
  return items as unknown as Item[] | undefined;
}

const STATUS_DOC = '---\ntitle: t\nstatus: d\n---\n本文';
const CATEGORIES_DOC = '---\ncategories:\n  - t\n---\n本文';
const TAGS_DOC = '---\ntags:\n  - a\n---\n本文';

beforeEach(async () => {
  resetMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  context = createContext();
  provider = new FrontMatterCompletionProvider(context);
  await loginAs(context);
  await selectProject(context, 7);
  server.respondWith(
    routes({
      'GET /api/metadata/post-statuses': jsonReply([
        { value: 'draft', label: '下書き' },
        { value: 'publish', label: '公開' },
      ]),
      'GET /api/projects/7/article-plan/categories': jsonReply(['tech', 'life']),
      'GET /api/projects/7/article-plan/tags': jsonReply(['ai']),
    })
  );
});

afterEach(async () => {
  await server.stop();
});

describe('FrontMatterCompletionProvider', () => {
  it('statusの値には、サーバーの投稿ステータスを表示名つきで候補に出し、置換範囲を付ける', async () => {
    const items = await complete(STATUS_DOC, 2, 'status: d'.length);

    expect(items?.map((i) => [i.label, i.detail])).toEqual([
      ['draft', '下書き'],
      ['publish', '公開'],
    ]);
    expect(items?.every((i) => i.kind === CompletionItemKind.EnumMember)).toBe(true);
    expect(items?.[0].range).toMatchObject({ start: { character: 8 }, end: { character: 9 } });
  });

  it('categoriesのリスト項目には、プロジェクトの既存カテゴリを候補に出す', async () => {
    const items = await complete(CATEGORIES_DOC, 2, '  - t'.length);

    expect(items?.map((i) => i.label)).toEqual(['tech', 'life']);
  });

  it('tagsのリスト項目には、プロジェクトの既存タグを候補に出す', async () => {
    const items = await complete(TAGS_DOC, 2, '  - a'.length);

    expect(items?.map((i) => i.label)).toEqual(['ai']);
  });

  it('markdown以外のドキュメントでは何も返さない', async () => {
    expect(await complete(STATUS_DOC, 2, 9, 'plaintext')).toBeUndefined();
    expect(server.recorded).toHaveLength(0);
  });

  it('補完の対象でない位置では何も返さず、サーバーへも問い合わせない', async () => {
    expect(await complete(STATUS_DOC, 1, 3)).toBeUndefined();
    expect(server.recorded).toHaveLength(0);
  });

  it('未ログインなら何も返さない', async () => {
    context.secretValues.clear();

    expect(await complete(STATUS_DOC, 2, 9)).toBeUndefined();
    expect(server.recorded).toHaveLength(0);
  });

  it('カテゴリ・タグの補完は、ログインユーザーかプロジェクトが分からなければ空の候補を返す', async () => {
    context.stateValues.clear();

    expect(await complete(CATEGORIES_DOC, 2, 5)).toEqual([]);
    expect(await complete(TAGS_DOC, 2, 5)).toEqual([]);
    expect(server.recorded).toHaveLength(0);
  });

  it('ログインユーザーが分からない場合も、カテゴリの候補は空になる', async () => {
    const noActor = createContext();
    await loginAs(noActor, null);
    await selectProject(noActor, 7);
    provider = new FrontMatterCompletionProvider(noActor);

    expect(await complete(CATEGORIES_DOC, 2, 5)).toEqual([]);
  });

  it('候補の取得に失敗したら、補完は出さずに終わる(例外にしない)', async () => {
    server.respondWith({ status: 403, body: 'forbidden' });

    expect(await complete(STATUS_DOC, 2, 9)).toBeUndefined();
  });
});
