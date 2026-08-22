import * as vscode from 'vscode';
import { getApiKey, getActor, getProjectId, getServerUrl } from './config';
import * as api from './apiClient';
import { detectBodyCustomTagCompletionContext } from './bodyCustomTagCompletionLogic';
import { logger } from './logger';
import { messageOf } from './errorHandler';

/**
 * 本文中のカスタムタグ(`[tagname]〜[/tagname]`)へコード補完を提供する(issue #522)。
 *
 * 候補の取得先(listCustomTags)はapiClient.ts側で既にキャッシュ(LruCache)されているため、
 * ここで追加のキャッシュは持たない。
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

    const lines = document.getText().split(/\r?\n/);
    const completionContext = detectBodyCustomTagCompletionContext(lines, position.line, position.character);
    if (!completionContext) {
      return undefined;
    }

    const apiKey = await getApiKey(this.context);
    const actor = await getActor(this.context);
    const projectId = getProjectId(this.context);
    if (!apiKey || !actor || !projectId) {
      return undefined;
    }

    const range = new vscode.Range(
      new vscode.Position(position.line, completionContext.replaceStart),
      new vscode.Position(position.line, completionContext.replaceEnd)
    );

    try {
      const tags = await api.listCustomTags(getServerUrl(), apiKey, actor, projectId);
      return tags.map((tag) => {
        const item = new vscode.CompletionItem(tag.tagName, vscode.CompletionItemKind.Snippet);
        item.detail = tag.description ?? undefined;
        item.range = range;
        item.insertText = new vscode.SnippetString(
          tag.tagFormat === 'BLOCK' ? `${tag.tagName}]\n$0\n[/${tag.tagName}]` : `${tag.tagName}]$0[/${tag.tagName}]`
        );
        return item;
      });
    } catch (err) {
      logger.warn('カスタムタグ補完候補の取得に失敗しました', { reason: messageOf(err) });
      return undefined;
    }
  }
}
