import { SnippetString, resetMocks } from '../__mocks__/vscode';
import { BodyCustomTagCompletionProvider } from '../bodyCustomTagCompletionProvider';
import * as api from '../apiClient';
import { setConfiguration } from '../__mocks__/vscode';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './support/extensionContext';

// 外部通信(gateway の /api/custom-tags)だけを実サーバーで受ける。config.ts・apiClient.ts は実物。
let server: LocalHttpServer;
let context: TestContext;
let provider: BodyCustomTagCompletionProvider;

/** カスタムタグ一覧への要求の宛先(projectId クエリ)。 */
function requestedProjectIds(): string[] {
  return server.recorded.map((r) => new URL(r.path, 'http://x').searchParams.get('projectId') ?? '');
}

function doc(text: string, languageId = 'markdown') {
  return { languageId, getText: () => text };
}

function pos(line: number, character: number) {
  return { line, character };
}


async function complete(text: string, line = 0, character = text.split('\n')[line].length) {
  const items = await provider.provideCompletionItems(doc(text) as never, pos(line, character) as never);
  return items as unknown as { label: string; insertText?: string | SnippetString; filterText?: string }[] | undefined;
}

function snippetOf(item: { insertText?: string | SnippetString } | undefined): string | undefined {
  return item?.insertText instanceof SnippetString ? item.insertText.value : (item?.insertText as string | undefined);
}

describe('BodyCustomTagCompletionProvider', () => {
  beforeEach(async () => {
    resetMocks();
    api.clearResponseCache();
    server = await LocalHttpServer.start();
    setConfiguration('letsBlog.serverUrl', server.url);
    context = createContext();
    provider = new BodyCustomTagCompletionProvider(context);
    await loginAs(context);
    await selectProject(context, 1);
    server.respondWith(jsonReply([]));
  });

  afterEach(async () => {
    await server.stop();
  });

  it('AC1: 登録済みのカスタムタグ名が候補に出て、開始・終了タグを挿入する', async () => {
    server.respondWith(
      jsonReply([
        { tagName: 'warn', description: '警告', tagFormat: 'BLOCK' },
        { tagName: 'kbd', description: null, tagFormat: 'INLINE' },
      ])
    );

    const items = await complete('本文[');

    const labels = items!.map((i) => i.label);
    expect(labels).toEqual(expect.arrayContaining(['warn', 'kbd']));
    expect(snippetOf(items!.find((i) => i.label === 'warn'))).toBe('warn]\n$0\n[/warn]');
    expect(snippetOf(items!.find((i) => i.label === 'kbd'))).toBe('kbd]$0[/kbd]');
  });

  it('AC2: カスタムタグが0件でも組み込みタグ(toc/blogcard/amazon)が候補に出る', async () => {
    const items = await complete('本文[');

    expect(items!.map((i) => i.label)).toEqual(expect.arrayContaining(['toc', 'blogcard', 'amazon']));
    expect(snippetOf(items!.find((i) => i.label === 'toc'))).toBe('toc]');
    expect(snippetOf(items!.find((i) => i.label === 'blogcard'))).toBe('blogcard ${1:URL}]');
    expect(snippetOf(items!.find((i) => i.label === 'amazon'))).toBe('amazon ${1:URL}]');
  });

  it('AC2: 候補取得に失敗しても組み込みタグは出る', async () => {
    server.respondWith({ status: 403, body: 'boom' });

    const items = await complete('本文[');

    expect(items!.map((i) => i.label)).toEqual(expect.arrayContaining(['toc', 'blogcard', 'amazon']));
  });

  it('AC3: 未ログインのとき、ログインを促す非挿入の案内候補が出る', async () => {
    context.secretValues.clear();

    const items = await complete('本文[');

    const guidance = items!.find((i) => /ログイン/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
    expect(guidance!.filterText).toBeDefined();
    expect(server.recorded).toHaveLength(0);
  });

  it('AC3: プロジェクト未選択のとき、プロジェクト選択を促す案内候補が出る', async () => {
    context.stateValues.clear();

    const items = await complete('本文[');

    const guidance = items!.find((i) => /プロジェクト/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
    expect(server.recorded).toHaveLength(0);
  });

  it('AC3: 候補取得のAPIが失敗したとき、原因がわかる案内候補が出る', async () => {
    server.respondWith({ status: 403, body: 'boom' });

    const items = await complete('本文[');

    const guidance = items!.find((i) => /取得できません/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
  });

  it('プロジェクトはfrontmatterのproject_idを優先し、無ければworkspaceStateを使う', async () => {
    await complete('---\nproject_id: 7\n---\n本文[', 3);
    expect(requestedProjectIds()).toEqual(['7']);
    expect(server.recorded[0].headers.authorization).toBe('Bearer access-token');

    await complete('---\ntitle: x\n---\n本文[', 3);
    expect(requestedProjectIds()).toEqual(['7', '1']);
  });

  it('AC5: frontmatter内では本文カスタムタグの補完を出さない', async () => {
    const items = await complete('---\ntitle: [\n---\n本文', 1, 'title: ['.length);

    expect(items).toBeUndefined();
    expect(server.recorded).toHaveLength(0);
  });

  it('markdown以外、`[`の文脈でない位置では何も返さない', async () => {
    expect(await provider.provideCompletionItems(doc('[', 'plaintext') as never, pos(0, 1) as never)).toBeUndefined();
    expect(await complete('本文です')).toBeUndefined();
  });
});
