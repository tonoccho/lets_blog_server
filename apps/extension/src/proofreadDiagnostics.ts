import * as vscode from 'vscode';
import { getActor, getAccessToken, getConfiguredAiProvider, getProjectId } from './config';
import { parseArticle, validateScheduledPublication } from './frontMatter';
import * as api from './apiClient';
import { CancelledError, messageOf } from './errorHandler';
import { logger } from './logger';
import {
  computeBodyOffset,
  findFrontMatterFieldLine,
  findInvalidCategories,
  isValidStatus,
  locateContentIssues,
} from './proofreadLogic';
import { ProofreadIssue } from './schemas';

/**
 * 校正チェック(issue #523)のvscode連携部分。
 *
 * front matterの検証(publish_scheduled_atの過去日時・statusの妥当性・categoriesの実在確認)は
 * LLMを呼ばないため常時・短いデバウンスで実行する。一方、本文のAI校正(誤字脱字・読みやすさ・
 * 冗長表現)は編集の都度、設定した外部AIプロバイダーへ本文を送信することになるため、
 * letsBlog.proofreadEnabled(既定false)でオプトインした場合のみ実行する
 * (「Proofread Now」コマンドは設定に関わらず両方を即時実行する)。
 */

const DIAGNOSTIC_SOURCE_CONTENT = 'letsBlog-proofread';
const DIAGNOSTIC_SOURCE_FRONTMATTER = 'letsBlog-frontmatter';
const FRONT_MATTER_DEBOUNCE_MS = 500;
const DEFAULT_CONTENT_DEBOUNCE_MS = 2000;

const CONTENT_ISSUE_LABELS: Record<string, string> = {
  typo: '誤字脱字',
  readability: '読みやすさ',
  unnecessary: '冗長な表現',
};

type ProofreadFix =
  | { range: vscode.Range; kind: 'applySuggestion'; suggestion: string }
  | { range: vscode.Range; kind: 'schedulePublication' }
  | { range: vscode.Range; kind: 'fixInvalidStatus' }
  | { range: vscode.Range; kind: 'removeInvalidCategory'; category: string };

function isProofreadEnabled(): boolean {
  return vscode.workspace.getConfiguration('letsBlog').get<boolean>('proofreadEnabled', false);
}

function getContentDebounceMs(): number {
  const configured = vscode.workspace
    .getConfiguration('letsBlog')
    .get<number>('proofreadDebounceMs', DEFAULT_CONTENT_DEBOUNCE_MS);
  return typeof configured === 'number' && configured > 0 ? configured : DEFAULT_CONTENT_DEBOUNCE_MS;
}

function toRange(location: { line: number; startColumn: number; endColumn: number }): vscode.Range {
  return new vscode.Range(
    new vscode.Position(location.line, location.startColumn),
    new vscode.Position(location.line, location.endColumn)
  );
}

function buildDiagnostic(range: vscode.Range, message: string, source: string, code: string): vscode.Diagnostic {
  const diagnostic = new vscode.Diagnostic(range, message, vscode.DiagnosticSeverity.Error);
  diagnostic.source = source;
  diagnostic.code = code;
  return diagnostic;
}

function formatContentMessage(issue: ProofreadIssue): string {
  const label = CONTENT_ISSUE_LABELS[issue.type] ?? issue.type;
  return issue.suggestion ? `[${label}] ${issue.message}(提案: ${issue.suggestion})` : `[${label}] ${issue.message}`;
}

/**
 * エディタの校正診断(赤い波線)を管理する。1インスタンスを拡張全体で共有し、
 * ドキュメントURIごとにデバウンスタイマー・進行中リクエスト・診断内容を保持する。
 */
export class ProofreadController implements vscode.Disposable, vscode.CodeActionProvider {
  private readonly diagnostics = vscode.languages.createDiagnosticCollection('letsBlog-proofread');
  private readonly frontMatterTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly contentTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly contentAbortControllers = new Map<string, AbortController>();
  private readonly frontMatterDiagnostics = new Map<string, vscode.Diagnostic[]>();
  private readonly contentDiagnostics = new Map<string, vscode.Diagnostic[]>();
  private readonly frontMatterFixes = new Map<string, ProofreadFix[]>();
  private readonly contentFixes = new Map<string, ProofreadFix[]>();

  constructor(private readonly context: vscode.ExtensionContext) {}

  dispose(): void {
    for (const timer of this.frontMatterTimers.values()) clearTimeout(timer);
    for (const timer of this.contentTimers.values()) clearTimeout(timer);
    for (const controller of this.contentAbortControllers.values()) controller.abort();
    this.diagnostics.dispose();
  }

  /** ドキュメントが閉じられた際に、蓄積した状態を破棄する。 */
  clearDocument(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    const fmTimer = this.frontMatterTimers.get(key);
    if (fmTimer) clearTimeout(fmTimer);
    const contentTimer = this.contentTimers.get(key);
    if (contentTimer) clearTimeout(contentTimer);
    this.contentAbortControllers.get(key)?.abort();
    this.frontMatterTimers.delete(key);
    this.contentTimers.delete(key);
    this.contentAbortControllers.delete(key);
    this.frontMatterDiagnostics.delete(key);
    this.contentDiagnostics.delete(key);
    this.frontMatterFixes.delete(key);
    this.contentFixes.delete(key);
    this.diagnostics.delete(document.uri);
  }

  /** 編集/オープンの度に呼ばれる。front matterチェックは常時、本文AI校正はオプトイン時のみ予約する。 */
  scheduleCheck(document: vscode.TextDocument): void {
    if (document.languageId !== 'markdown') return;
    const key = document.uri.toString();

    const existingFmTimer = this.frontMatterTimers.get(key);
    if (existingFmTimer) clearTimeout(existingFmTimer);
    this.frontMatterTimers.set(
      key,
      setTimeout(() => {
        this.frontMatterTimers.delete(key);
        void this.runFrontMatterCheck(document);
      }, FRONT_MATTER_DEBOUNCE_MS)
    );

    if (!isProofreadEnabled()) return;
    const existingContentTimer = this.contentTimers.get(key);
    if (existingContentTimer) clearTimeout(existingContentTimer);
    this.contentTimers.set(
      key,
      setTimeout(() => {
        this.contentTimers.delete(key);
        void this.runContentCheck(document);
      }, getContentDebounceMs())
    );
  }

  /** 「Proofread Now」コマンド用。設定(letsBlog.proofreadEnabled)に関わらず両方を即時実行する。 */
  async runManual(document: vscode.TextDocument): Promise<void> {
    const key = document.uri.toString();
    const fmTimer = this.frontMatterTimers.get(key);
    if (fmTimer) {
      clearTimeout(fmTimer);
      this.frontMatterTimers.delete(key);
    }
    const contentTimer = this.contentTimers.get(key);
    if (contentTimer) {
      clearTimeout(contentTimer);
      this.contentTimers.delete(key);
    }

    await this.runFrontMatterCheck(document);
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '校正チェック中…' },
      () => this.runContentCheck(document, { manual: true })
    );
  }

  private applyDiagnostics(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    const combined = [...(this.frontMatterDiagnostics.get(key) ?? []), ...(this.contentDiagnostics.get(key) ?? [])];
    this.diagnostics.set(document.uri, combined);
  }

  private async runFrontMatterCheck(document: vscode.TextDocument): Promise<void> {
    const key = document.uri.toString();
    const rawText = document.getText();
    const article = parseArticle(rawText);
    const diagnostics: vscode.Diagnostic[] = [];
    const fixes: ProofreadFix[] = [];

    const scheduledValidation = validateScheduledPublication(article.data.publish_scheduled_at, new Date(), {
      requireFuture: true,
    });
    if (scheduledValidation.error) {
      const location = findFrontMatterFieldLine(rawText, 'publish_scheduled_at');
      if (location) {
        const range = toRange(location);
        diagnostics.push(
          buildDiagnostic(range, scheduledValidation.error, DIAGNOSTIC_SOURCE_FRONTMATTER, 'scheduled-past')
        );
        fixes.push({ range, kind: 'schedulePublication' });
      }
    }

    const apiKey = await getAccessToken(this.context);
    if (apiKey) {
      try {
        const statuses = await api.getPostStatuses(apiKey);
        if (!isValidStatus(article.data.status, statuses.map((s) => s.value))) {
          const location = findFrontMatterFieldLine(rawText, 'status');
          if (location) {
            const range = toRange(location);
            diagnostics.push(
              buildDiagnostic(
                range,
                `statusの値が不正です: ${article.data.status}`,
                DIAGNOSTIC_SOURCE_FRONTMATTER,
                'invalid-status'
              )
            );
            fixes.push({ range, kind: 'fixInvalidStatus' });
          }
        }
      } catch (err) {
        logger.warn('投稿ステータス一覧の取得に失敗しました(校正チェックをスキップ)', { reason: messageOf(err) });
      }

      const actor = await getActor(this.context);
      const projectId = (article.data.project_id as number | undefined) ?? getProjectId(this.context);
      if (actor && projectId) {
        try {
          const existingCategories = await api.listExistingCategories(apiKey, actor, projectId);
          const invalidCategories = findInvalidCategories(article.data.categories, existingCategories);
          if (invalidCategories.length > 0) {
            const location = findFrontMatterFieldLine(rawText, 'categories');
            if (location) {
              const range = toRange(location);
              diagnostics.push(
                buildDiagnostic(
                  range,
                  `サイトに存在しないカテゴリが含まれています: ${invalidCategories.join(', ')}`,
                  DIAGNOSTIC_SOURCE_FRONTMATTER,
                  'invalid-category'
                )
              );
              for (const category of invalidCategories) {
                fixes.push({ range, kind: 'removeInvalidCategory', category });
              }
            }
          }
        } catch (err) {
          logger.warn('カテゴリ一覧の取得に失敗しました(校正チェックをスキップ)', { reason: messageOf(err) });
        }
      }
    }

    this.frontMatterDiagnostics.set(key, diagnostics);
    this.frontMatterFixes.set(key, fixes);
    this.applyDiagnostics(document);
  }

  private async runContentCheck(document: vscode.TextDocument, options: { manual?: boolean } = {}): Promise<void> {
    const key = document.uri.toString();
    const rawText = document.getText();
    const article = parseArticle(rawText);

    if (!article.content.trim()) {
      this.contentDiagnostics.set(key, []);
      this.contentFixes.set(key, []);
      this.applyDiagnostics(document);
      return;
    }

    this.contentAbortControllers.get(key)?.abort();
    const controller = new AbortController();
    this.contentAbortControllers.set(key, controller);

    try {
      const apiKey = await getAccessToken(this.context);
      if (!apiKey) {
        if (options.manual) {
          throw new Error("ログインしていません。「Let's Blog: Login」を先に実行してください。");
        }
        return;
      }
      const actor = await getActor(this.context);
      const provider = getConfiguredAiProvider();
      const result = await api.proofreadContent(
        apiKey,
        actor,
        article.content,
        provider || undefined,
        controller.signal
      );
      // 実行中に別のスケジュールが割り込んでいた場合、古い結果で上書きしない。
      if (this.contentAbortControllers.get(key) !== controller) {
        return;
      }

      const bodyOffset = computeBodyOffset(rawText);
      const located = locateContentIssues(article.content, bodyOffset, result.issues);
      const diagnostics: vscode.Diagnostic[] = [];
      const fixes: ProofreadFix[] = [];
      for (const item of located) {
        const range = new vscode.Range(document.positionAt(item.startOffset), document.positionAt(item.endOffset));
        diagnostics.push(buildDiagnostic(range, formatContentMessage(item.issue), DIAGNOSTIC_SOURCE_CONTENT, item.issue.type));
        if (item.issue.suggestion) {
          fixes.push({ range, kind: 'applySuggestion', suggestion: item.issue.suggestion });
        }
      }
      this.contentDiagnostics.set(key, diagnostics);
      this.contentFixes.set(key, fixes);
      this.applyDiagnostics(document);
    } catch (err) {
      if (err instanceof CancelledError) {
        return;
      }
      if (options.manual) {
        throw err;
      }
      // バックグラウンドの自動チェック失敗はタイピング中に通知を出すと煩わしいため、ログのみに残す。
      logger.warn('校正チェックに失敗しました(バックグラウンド実行のためスキップ)', { reason: messageOf(err) });
    } finally {
      if (this.contentAbortControllers.get(key) === controller) {
        this.contentAbortControllers.delete(key);
      }
    }
  }

  provideCodeActions(
    document: vscode.TextDocument,
    _range: vscode.Range,
    context: vscode.CodeActionContext
  ): vscode.CodeAction[] {
    const key = document.uri.toString();
    const fixes = [...(this.frontMatterFixes.get(key) ?? []), ...(this.contentFixes.get(key) ?? [])];
    const actions: vscode.CodeAction[] = [];
    for (const diagnostic of context.diagnostics) {
      if (diagnostic.source !== DIAGNOSTIC_SOURCE_CONTENT && diagnostic.source !== DIAGNOSTIC_SOURCE_FRONTMATTER) {
        continue;
      }
      for (const fix of fixes.filter((f) => f.range.isEqual(diagnostic.range))) {
        actions.push(this.buildCodeAction(document, diagnostic, fix));
      }
    }
    return actions;
  }

  private buildCodeAction(document: vscode.TextDocument, diagnostic: vscode.Diagnostic, fix: ProofreadFix): vscode.CodeAction {
    switch (fix.kind) {
      case 'applySuggestion': {
        const action = new vscode.CodeAction('提案を適用', vscode.CodeActionKind.QuickFix);
        action.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.replace(document.uri, fix.range, fix.suggestion);
        action.edit = edit;
        return action;
      }
      case 'schedulePublication': {
        const action = new vscode.CodeAction('公開予定日時を設定し直す…', vscode.CodeActionKind.QuickFix);
        action.diagnostics = [diagnostic];
        action.command = { command: 'letsBlog.schedulePublication', title: '公開予定日時を設定し直す' };
        return action;
      }
      case 'fixInvalidStatus': {
        const action = new vscode.CodeAction('有効なステータスを選択…', vscode.CodeActionKind.QuickFix);
        action.diagnostics = [diagnostic];
        action.command = {
          command: 'letsBlog.fixInvalidStatus',
          title: '有効なステータスを選択',
          arguments: [document.uri],
        };
        return action;
      }
      case 'removeInvalidCategory': {
        const action = new vscode.CodeAction(`存在しないカテゴリ「${fix.category}」を削除`, vscode.CodeActionKind.QuickFix);
        action.diagnostics = [diagnostic];
        action.command = {
          command: 'letsBlog.removeInvalidCategory',
          title: 'カテゴリを削除',
          arguments: [document.uri, fix.category],
        };
        return action;
      }
    }
  }
}
