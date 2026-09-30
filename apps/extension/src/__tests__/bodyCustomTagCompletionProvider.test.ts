import { SnippetString, resetMocks } from '../__mocks__/vscode';
import { BodyCustomTagCompletionProvider } from '../bodyCustomTagCompletionProvider';
import * as api from '../apiClient';
import { getAccessToken, getActor, getProjectId } from '../config';

jest.mock('../apiClient', () => ({ listCustomTags: jest.fn() }));
jest.mock('../config', () => ({
  getAccessToken: jest.fn(),
  getActor: jest.fn(),
  getProjectId: jest.fn(),
}));

const listCustomTags = api.listCustomTags as jest.Mock;
const accessToken = getAccessToken as jest.Mock;
const actor = getActor as jest.Mock;
const projectIdState = getProjectId as jest.Mock;

function doc(text: string, languageId = 'markdown') {
  return { languageId, getText: () => text };
}

function pos(line: number, character: number) {
  return { line, character };
}

const ACTOR = { id: 'u1' };
const provider = new BodyCustomTagCompletionProvider({} as never);

async function complete(text: string, line = 0, character = text.split('\n')[line].length) {
  const items = await provider.provideCompletionItems(doc(text) as never, pos(line, character) as never);
  return items as unknown as { label: string; insertText?: string | SnippetString; filterText?: string }[] | undefined;
}

function snippetOf(item: { insertText?: string | SnippetString } | undefined): string | undefined {
  return item?.insertText instanceof SnippetString ? item.insertText.value : (item?.insertText as string | undefined);
}

describe('BodyCustomTagCompletionProvider', () => {
  beforeEach(() => {
    resetMocks();
    jest.resetAllMocks();
    accessToken.mockResolvedValue('token');
    actor.mockResolvedValue(ACTOR);
    projectIdState.mockReturnValue(1);
    listCustomTags.mockResolvedValue([]);
  });

  it('AC1: 登録済みのカスタムタグ名が候補に出て、開始・終了タグを挿入する', async () => {
    listCustomTags.mockResolvedValue([
      { tagName: 'warn', description: '警告', tagFormat: 'BLOCK' },
      { tagName: 'kbd', description: null, tagFormat: 'INLINE' },
    ]);

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
    listCustomTags.mockRejectedValue(new Error('boom'));

    const items = await complete('本文[');

    expect(items!.map((i) => i.label)).toEqual(expect.arrayContaining(['toc', 'blogcard', 'amazon']));
  });

  it('AC3: 未ログインのとき、ログインを促す非挿入の案内候補が出る', async () => {
    accessToken.mockResolvedValue(undefined);

    const items = await complete('本文[');

    const guidance = items!.find((i) => /ログイン/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
    expect(guidance!.filterText).toBeDefined();
    expect(listCustomTags).not.toHaveBeenCalled();
  });

  it('AC3: プロジェクト未選択のとき、プロジェクト選択を促す案内候補が出る', async () => {
    projectIdState.mockReturnValue(undefined);

    const items = await complete('本文[');

    const guidance = items!.find((i) => /プロジェクト/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
    expect(listCustomTags).not.toHaveBeenCalled();
  });

  it('AC3: 候補取得のAPIが失敗したとき、原因がわかる案内候補が出る', async () => {
    listCustomTags.mockRejectedValue(new Error('boom'));

    const items = await complete('本文[');

    const guidance = items!.find((i) => /取得できません/.test(i.label));
    expect(guidance).toBeDefined();
    expect(snippetOf(guidance)).toBe('');
  });

  it('プロジェクトはfrontmatterのproject_idを優先し、無ければworkspaceStateを使う', async () => {
    projectIdState.mockReturnValue(1);
    await complete('---\nproject_id: 7\n---\n本文[', 3);
    expect(listCustomTags).toHaveBeenLastCalledWith('token', ACTOR, 7);

    await complete('---\ntitle: x\n---\n本文[', 3);
    expect(listCustomTags).toHaveBeenLastCalledWith('token', ACTOR, 1);
  });

  it('AC5: frontmatter内では本文カスタムタグの補完を出さない', async () => {
    const items = await complete('---\ntitle: [\n---\n本文', 1, 'title: ['.length);

    expect(items).toBeUndefined();
    expect(listCustomTags).not.toHaveBeenCalled();
  });

  it('markdown以外、`[`の文脈でない位置では何も返さない', async () => {
    expect(await provider.provideCompletionItems(doc('[', 'plaintext') as never, pos(0, 1) as never)).toBeUndefined();
    expect(await complete('本文です')).toBeUndefined();
  });
});
