import * as vscode from 'vscode';
import { getApiKey, getActor, getProjectId, getServerUrl } from './config';
import * as api from './apiClient';
import { detectFrontMatterCompletionContext, FrontMatterCompletionField } from './frontMatterCompletionLogic';
import { logger } from './logger';
import { messageOf } from './errorHandler';

/**
 * frontmatterのstatus/categories/tagsへコード補完を提供する(issue #521)。
 *
 * 候補の取得先(getPostStatuses/listExistingCategories/listExistingTags)はいずれも
 * proofreadDiagnostics.tsの検証で使っているものと同じで、apiClient.ts側で既にキャッシュ
 * (LruCache)されているため、ここで追加のキャッシュは持たない。
 */
export class FrontMatterCompletionProvider implements vscode.CompletionItemProvider {
  constructor(private readonly context: vscode.ExtensionContext) {}

  async provideCompletionItems(
    document: vscode.TextDocument,
    position: vscode.Position
  ): Promise<vscode.CompletionItem[] | undefined> {
    if (document.languageId !== 'markdown') {
      return undefined;
    }

    const lines = document.getText().split(/\r?\n/);
    const completionContext = detectFrontMatterCompletionContext(lines, position.line, position.character);
    if (!completionContext) {
      return undefined;
    }

    const apiKey = await getApiKey(this.context);
    if (!apiKey) {
      return undefined;
    }

    const range = new vscode.Range(
      new vscode.Position(position.line, completionContext.replaceStart),
      new vscode.Position(position.line, completionContext.replaceEnd)
    );

    try {
      const items = await this.loadCandidates(completionContext.field, apiKey);
      return items.map(({ value, detail }) => {
        const item = new vscode.CompletionItem(value, vscode.CompletionItemKind.EnumMember);
        item.detail = detail;
        item.range = range;
        return item;
      });
    } catch (err) {
      logger.warn('frontmatter補完候補の取得に失敗しました', {
        field: completionContext.field,
        reason: messageOf(err),
      });
      return undefined;
    }
  }

  private async loadCandidates(
    field: FrontMatterCompletionField,
    apiKey: string
  ): Promise<{ value: string; detail?: string }[]> {
    const serverUrl = getServerUrl();

    if (field === 'status') {
      const statuses = await api.getPostStatuses(serverUrl, apiKey);
      return statuses.map((status) => ({ value: status.value, detail: status.label }));
    }

    const actor = await getActor(this.context);
    const projectId = getProjectId(this.context);
    if (!actor || !projectId) {
      return [];
    }

    const values =
      field === 'categories'
        ? await api.listExistingCategories(serverUrl, apiKey, actor, projectId)
        : await api.listExistingTags(serverUrl, apiKey, actor, projectId);
    return values.map((value) => ({ value }));
  }
}
