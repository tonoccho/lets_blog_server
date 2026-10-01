import * as vscode from 'vscode';
import { getActor, getAccessToken, getProjectId, requireProjectId } from './config';
import { parseArticle, validateScheduledPublication } from './frontMatter';
import * as api from './apiClient';
import { messageOf } from './errorHandler';
import { logger } from './logger';
import {
  buildFindingHover,
  computeBodyOffset,
  findFrontMatterFieldLine,
  findInvalidCategories,
  isValidStatus,
  REVIEW_STEPS,
  reviewOutcomeMessages,
  reviewProgressMessage,
  ReviewStepKey,
  runReviewSteps,
  StepFinding,
} from './proofreadLogic';
import { computeBodyHash, ReviewChecklistDocumentState } from './reviewChecklistLogic';
import { PreviewReviewOutcome, reviewBeforePreview } from './previewReviewLogic';
import { PublishReviewInput, PublishReviewOutcome, reviewBeforePublish } from './publishReviewLogic';
import { ReviewChecklistPanel } from './reviewChecklistPanel';
import { ReviewChecklistStore } from './reviewChecklistStore';

/**
 * 校正チェック(issue #523)と多段レビュー(issue #1215)のvscode連携部分。
 *
 * front matterの検証(publish_scheduled_atの過去日時・statusの妥当性・categoriesの実在確認)は
 * LLMを呼ばないため、編集の都度・短いデバウンスで実行する。
 *
 * 本文のレビュー(日本語チェック→校正チェック→校閲→読者視点でのチェック→文体チェックの5ステップ)は
 * LLMを呼ぶため自動実行せず、「Proofread Now」コマンドの明示的な操作だけを起点にする。
 * 結果はステップ別の色のアンダーライン(TextEditorDecorationType)で示す。
 * DiagnosticはDiagnosticSeverityでしか色を変えられずステップ別の色分けができないため、
 * 本文の指摘にはDiagnosticを使わない。
 */

const DIAGNOSTIC_SOURCE_FRONTMATTER = 'letsBlog-frontmatter';
const FRONT_MATTER_DEBOUNCE_MS = 500;

type ProofreadFix =
  | { range: vscode.Range; kind: 'schedulePublication' }
  | { range: vscode.Range; kind: 'fixInvalidStatus' }
  | { range: vscode.Range; kind: 'removeInvalidCategory'; category: string };

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

function createStepDecorationType(colorId: string): vscode.TextEditorDecorationType {
  const color = new vscode.ThemeColor(colorId);
  return vscode.window.createTextEditorDecorationType({
    borderStyle: 'none none solid none',
    borderWidth: '0 0 2px 0',
    borderColor: color,
    overviewRulerColor: color,
  });
}

/**
 * エディタの校正表示を管理する。1インスタンスを拡張全体で共有し、ドキュメントURIごとに
 * front matterのデバウンスタイマー・診断内容と、本文レビューの進行中リクエスト・装飾内容を保持する。
 */
export class ProofreadController implements vscode.Disposable, vscode.CodeActionProvider {
  private readonly diagnostics = vscode.languages.createDiagnosticCollection('letsBlog-proofread');
  private readonly frontMatterTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly contentAbortControllers = new Map<string, AbortController>();
  private readonly frontMatterDiagnostics = new Map<string, vscode.Diagnostic[]>();
  private readonly frontMatterFixes = new Map<string, ProofreadFix[]>();
  /** ステップ別の装飾タイプ。ステップ数は固定のため、生成は1回だけ。 */
  private readonly stepDecorationTypes = new Map<ReviewStepKey, vscode.TextEditorDecorationType>(
    REVIEW_STEPS.map((step) => [step.key, createStepDecorationType(step.colorId)])
  );
  /** ドキュメントごと・ステップごとの、直近のレビュー結果の装飾(エディタを開き直したときの再表示用)。 */
  private readonly findingRanges = new Map<string, vscode.DecorationOptions[][]>();

  /**
   * 指摘チェックリスト(issue #1216)の永続化。省略可能なのは、既存の呼び出し元/テストを
   * 壊さないため(チェックリスト機能が無くても本文レビュー自体は従来どおり動く)。
   */
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly checklistStore?: ReviewChecklistStore
  ) {}

  dispose(): void {
    for (const timer of this.frontMatterTimers.values()) clearTimeout(timer);
    for (const controller of this.contentAbortControllers.values()) controller.abort();
    for (const type of this.stepDecorationTypes.values()) type.dispose();
    this.diagnostics.dispose();
  }

  /** ドキュメントが閉じられた際に、蓄積した状態を破棄する。 */
  clearDocument(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    const fmTimer = this.frontMatterTimers.get(key);
    if (fmTimer) clearTimeout(fmTimer);
    this.contentAbortControllers.get(key)?.abort();
    this.frontMatterTimers.delete(key);
    this.contentAbortControllers.delete(key);
    this.frontMatterDiagnostics.delete(key);
    this.frontMatterFixes.delete(key);
    this.findingRanges.delete(key);
    this.diagnostics.delete(document.uri);
  }

  /**
   * 編集/オープンの度に呼ばれる。front matterチェックだけをデバウンスして予約する。
   * 本文のレビューはLLMを呼ぶため、ここからは決して起動しない(issue #1215)。
   */
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
  }

  /** 「Proofread Now」コマンド用。front matter検証を即時実行したうえで、本文の5ステップレビューを実行する。 */
  async runManual(document: vscode.TextDocument): Promise<void> {
    const key = document.uri.toString();
    const fmTimer = this.frontMatterTimers.get(key);
    if (fmTimer) {
      clearTimeout(fmTimer);
      this.frontMatterTimers.delete(key);
    }

    await this.runFrontMatterCheck(document);
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '校正チェック中…', cancellable: true },
      (progress, token) => this.runContentReview(document, (message) => progress.report({ message }), token)
    );
  }

  /**
   * Publishの直前(issue #1217)に呼ぶ。本文が直近のレビュー以降変わっていなければ保持した結果を使い
   * (LLMへは依頼しない)、変わっていれば5ステップのレビューを実行して未対応の件数を判定する。
   * レビュー結果を保持できない場合は、未対応の有無を判定できないため例外にして投稿を通さない。
   */
  async reviewForPublish(document: vscode.TextDocument): Promise<PublishReviewOutcome> {
    return reviewBeforePublish(this.reviewInput(document, 'Publish前のレビュー中…'));
  }

  /**
   * プレビューの直前(issue #1226)に呼ぶ。本文未変更の判定はPublishと同じ。未対応の指摘があっても
   * 止めず、失敗しても例外にしない(プレビューは常に表示する)。
   */
  async reviewForPreview(document: vscode.TextDocument): Promise<PreviewReviewOutcome> {
    return reviewBeforePreview(this.reviewInput(document, 'プレビュー前のレビュー中…'));
  }

  private reviewInput(document: vscode.TextDocument, progressTitle: string): PublishReviewInput {
    const key = document.uri.toString();
    const article = parseArticle(document.getText());
    return {
      content: article.content,
      snapshot: this.checklistStore?.get(key),
      runReview: async () => {
        const state = await vscode.window.withProgress(
          { location: vscode.ProgressLocation.Notification, title: progressTitle, cancellable: true },
          (progress, token) => this.runContentReview(document, (message) => progress.report({ message }), token)
        );
        if (!state) {
          throw new Error('レビュー結果を保持できなかったため、未対応の指摘を判定できません。もう一度お試しください。');
        }
        return state;
      },
    };
  }

  /** エディタが(再)表示されたとき、保持しているレビュー結果の装飾をそのエディタへ置き直す。 */
  refreshEditor(editor: vscode.TextEditor): void {
    const ranges = this.findingRanges.get(editor.document.uri.toString());
    if (!ranges) return;
    this.setDecorationsOn(editor, ranges);
  }

  private setDecorationsOn(editor: vscode.TextEditor, perStep: vscode.DecorationOptions[][]): void {
    REVIEW_STEPS.forEach((step, index) => {
      editor.setDecorations(this.stepDecorationTypes.get(step.key)!, perStep[index]);
    });
  }

  private applyDiagnostics(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    this.diagnostics.set(document.uri, this.frontMatterDiagnostics.get(key) ?? []);
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

  private async runContentReview(
    document: vscode.TextDocument,
    report: (message: string) => void,
    token?: vscode.CancellationToken
  ): Promise<ReviewChecklistDocumentState | undefined> {
    const key = document.uri.toString();
    const rawText = document.getText();
    const article = parseArticle(rawText);

    if (!article.content.trim()) {
      this.showFindings(document, []);
      return undefined;
    }

    this.contentAbortControllers.get(key)?.abort();
    const controller = new AbortController();
    this.contentAbortControllers.set(key, controller);
    // 利用者のキャンセル(issue #1224)。応答待ちのリクエストを中断し、以降のステップを実行させない。
    const cancelSubscription = token?.onCancellationRequested(() => controller.abort());

    try {
      const apiKey = await getAccessToken(this.context);
      if (!apiKey) {
        throw new Error("ログインしていません。「Let's Blog: Login」を先に実行してください。");
      }
      const actor = await getActor(this.context);
      const projectId = (article.data.project_id as number | undefined) ?? requireProjectId(this.context);
      const result = await runReviewSteps({
        content: article.content,
        bodyOffset: computeBodyOffset(rawText),
        signal: controller.signal,
        onStep: (step, index, total) => report(reviewProgressMessage(index, total, step.label)),
        fetchStep: async (stepKey, text) =>
          await api.reviewStepSuggestions(apiKey, actor, projectId, stepKey, text, controller.signal),
      });
      // 実行中に別のレビューが始まっていた場合、古い結果で上書きしない。
      if (this.contentAbortControllers.get(key) !== controller) {
        return undefined;
      }
      // 全ステップが失敗したなら、表示を空の結果で上書きせず、失敗として伝える。
      if (result.failures.length === REVIEW_STEPS.length) {
        throw result.failures[0].error;
      }
      const { findings } = result;
      this.showFindings(document, findings);
      const { warning, info } = reviewOutcomeMessages(result);
      if (warning) void vscode.window.showWarningMessage(warning);
      if (info) void vscode.window.showInformationMessage(info);
      // 失敗したステップがあるレビューは、全ステップを終えたものとして記録しない(Publishの判定を誤らせない)。
      if (!this.checklistStore || result.failures.length > 0) {
        return undefined;
      }
      const state = await this.checklistStore.recordReview(
        key,
        findings,
        computeBodyHash(article.content),
        result.skipped
      );
      ReviewChecklistPanel.refreshIfShowing(key, state.items, state.skippedSteps);
      return state;
    } catch (err) {
      if (this.contentAbortControllers.get(key) !== controller) {
        return undefined;
      }
      if (controller.signal.aborted) {
        // 利用者による中断。直前の表示・記録はそのまま残す。
        void vscode.window.showInformationMessage('レビューを中断しました。直前のレビュー結果の表示はそのままです。');
        return undefined;
      }
      throw err;
    } finally {
      cancelSubscription?.dispose();
      if (this.contentAbortControllers.get(key) === controller) {
        this.contentAbortControllers.delete(key);
      }
    }
  }

  /** レビュー結果をステップ別の装飾に変換して保持し、そのドキュメントを開いているエディタへ反映する。 */
  private showFindings(document: vscode.TextDocument, findings: StepFinding[]): void {
    const key = document.uri.toString();
    const perStep: vscode.DecorationOptions[][] = REVIEW_STEPS.map(() => []);
    for (const finding of findings) {
      const range = new vscode.Range(document.positionAt(finding.startOffset), document.positionAt(finding.endOffset));
      const hover = new vscode.MarkdownString(
        buildFindingHover(
          finding.step.label,
          finding.suggestion.message,
          finding.suggestion.suggestion,
          finding.suggestion.sources
        )
      );
      perStep[REVIEW_STEPS.indexOf(finding.step)].push({ range, hoverMessage: hover });
    }
    this.findingRanges.set(key, perStep);
    for (const editor of vscode.window.visibleTextEditors) {
      if (editor.document.uri.toString() === key) {
        this.setDecorationsOn(editor, perStep);
      }
    }
  }

  provideCodeActions(
    document: vscode.TextDocument,
    _range: vscode.Range,
    context: vscode.CodeActionContext
  ): vscode.CodeAction[] {
    const key = document.uri.toString();
    const fixes = this.frontMatterFixes.get(key) ?? [];
    const actions: vscode.CodeAction[] = [];
    for (const diagnostic of context.diagnostics) {
      if (diagnostic.source !== DIAGNOSTIC_SOURCE_FRONTMATTER) {
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
