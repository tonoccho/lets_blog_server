import * as vscode from 'vscode';
import { getAccessToken, getActor, getProjectId } from './config';
import * as api from './apiClient';
import { detectBodyCustomTagCompletionContext } from './bodyCustomTagCompletionLogic';
import { parseArticle } from './frontMatter';
import { logger } from './logger';
import { messageOf } from './errorHandler';

interface BuiltInTag {
  name: string;
  description: string;
  snippet: string;
}

/** サーバーが解釈する組み込みタグ。記法はカスタムタグの`[name]…[/name]`とは異なる。 */
const BUILT_IN_TAGS: BuiltInTag[] = [
  { name: 'toc', description: '組み込みタグ: 目次', snippet: 'toc]' },
  { name: 'blogcard', description: '組み込みタグ: ブログカード', snippet: 'blogcard ${1:URL}]' },
  { name: 'amazon', description: '組み込みタグ: Amazon商品リンク', snippet: 'amazon ${1:URL}]' },
];

/**
 * 本文中のタグ(組み込みタグと、`[tagname]〜[/tagname]`のカスタムタグ)へコード補完を提供する
 * (issue #522, #1467)。
 *
 * 候補の取得先(listCustomTags)はapiClient.ts側で既にキャッシュ(LruCache)されているため、
 * ここで追加のキャッシュは持たない。組み込みタグは常に出す。カスタムタグを取得できないとき
 * (未ログイン・プロジェクト未選択・取得失敗)は、何も挿入しない案内候補で原因と対処を示す。
 */
export class BodyCustomTagCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly context: vscode.ExtensionContext) {}

  async provideCompletionItems(
    document: vscode.TextDocument,
    position: vscode.Position
  ): Promise<vscode.CompletionItem[] | undefined> {
    if (document.languageId !== 'markdown') {
      return undefined;
    }

    const text = document.getText();
    const lines = text.split(/\r?\n/);
    const completionContext = detectBodyCustomTagCompletionContext(lines, position.line, position.character);
    if (!completionContext) {
      return undefined;
    }

    const range = new vscode.Range(
      new vscode.Position(position.line, completionContext.replaceStart),
      new vscode.Position(position.line, completionContext.replaceEnd)
    );
    const guidanceFilter = completionContext.prefix;
    const items = BUILT_IN_TAGS.map((tag) => this.toBuiltInItem(tag, range));

    const apiKey = await getAccessToken(this.context);
    const actor = await getActor(this.context);
    if (!apiKey || !actor) {
      logger.info('タグ補完: 未ログインのためカスタムタグを取得しません');
      items.push(this.toGuidanceItem('カスタムタグを表示するにはログインしてください(Let\'s Blog: Login)', range, guidanceFilter));
      return items;
    }

    const projectId = this.resolveProjectId(text);
    if (!projectId) {
      logger.info('タグ補完: プロジェクト未選択のためカスタムタグを取得しません');
      items.push(
        this.toGuidanceItem(
          'カスタムタグを表示するにはプロジェクトを選択してください(Let\'s Blog: Select Project / front matterのproject_id)',
          range,
          guidanceFilter
        )
      );
      return items;
    }

    try {
      const tags = await api.listCustomTags(apiKey, actor, projectId);
      for (const tag of tags) {
        const item = new vscode.CompletionItem(tag.tagName, vscode.CompletionItemKind.Snippet);
        item.detail = tag.description ?? undefined;
        item.range = range;
        item.insertText = new vscode.SnippetString(
          tag.tagFormat === 'BLOCK' ? `${tag.tagName}]\n$0\n[/${tag.tagName}]` : `${tag.tagName}]$0[/${tag.tagName}]`
        );
        items.push(item);
      }
    } catch (err) {
      logger.warn('カスタムタグ補完候補の取得に失敗しました', { reason: messageOf(err) });
      items.push(
        this.toGuidanceItem(
          'カスタムタグを取得できません。接続とログイン状態を確認してください(出力ログ「Let\'s Blog」参照)',
          range,
          guidanceFilter
        )
      );
    }
    return items;
  }

  /** front matterのproject_idを優先し、無ければ選択中のプロジェクト(workspaceState)を使う。 */
  private resolveProjectId(text: string): number | undefined {
    try {
      const fromFrontMatter = parseArticle(text).data.project_id;
      if (typeof fromFrontMatter === 'number') {
        return fromFrontMatter;
      }
    } catch (err) {
      logger.debug('タグ補完: front matterを解析できませんでした', { reason: messageOf(err) });
    }
    return getProjectId(this.context);
  }

  private toBuiltInItem(tag: BuiltInTag, range: vscode.Range): vscode.CompletionItem {
    const item = new vscode.CompletionItem(tag.name, vscode.CompletionItemKind.Keyword);
    item.detail = tag.description;
    item.range = range;
    item.insertText = new vscode.SnippetString(tag.snippet);
    return item;
  }

  /** 何も挿入しない案内。filterTextを入力済みの断片に合わせ、絞り込みで消えないようにする。 */
  private toGuidanceItem(label: string, range: vscode.Range, filterText: string): vscode.CompletionItem {
    const item = new vscode.CompletionItem(label, vscode.CompletionItemKind.Keyword);
    item.range = range;
    item.insertText = '';
    item.filterText = filterText;
    item.sortText = '~';
    return item;
  }
}
