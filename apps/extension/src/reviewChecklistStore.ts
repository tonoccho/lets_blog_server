import * as vscode from 'vscode';
import { StepFinding, StepSkip } from './proofreadLogic';
import {
  mergeChecklistState,
  ReviewChecklistDocumentState,
  ReviewChecklistStatus,
  setChecklistItemStatus,
} from './reviewChecklistLogic';

/** workspaceStateへ保存するキーの接頭辞。バージョンを刻んでおき、形式変更時に読み違えないようにする。 */
const STATE_KEY_PREFIX = 'letsBlog.reviewChecklist.v1:';

function stateKey(documentKey: string): string {
  return `${STATE_KEY_PREFIX}${documentKey}`;
}

/**
 * 指摘チェックリストの対応状態とレビュー結果の永続化(issue #1216)。
 *
 * サーバー側のテーブル・API・認可は追加せず、`context.workspaceState`へ
 * 記事ファイル単位(documentKey = document.uri.toString())で保存する。ワークスペースを
 * 開き直しても(VSCode再起動後も)残るため、restoreのための特別な読み込み処理は要らない。
 */
export class ReviewChecklistStore {
  constructor(private readonly context: vscode.ExtensionContext) {}

  /** 永続化済みの状態を返す。まだレビューを実行していない記事はundefined。 */
  get(documentKey: string): ReviewChecklistDocumentState | undefined {
    return this.context.workspaceState.get(stateKey(documentKey));
  }

  /**
   * レビュー1回分の結果を記録する。「ステップキー + 引用文 + 指摘内容」が一致する指摘には
   * 前回の対応状態を引き継ぎ(mergeChecklistState参照)、一致しない指摘は未対応から始まる。
   */
  async recordReview(
    documentKey: string,
    findings: StepFinding[],
    bodyHash: string,
    skipped: StepSkip[] = []
  ): Promise<ReviewChecklistDocumentState> {
    const merged = mergeChecklistState(this.get(documentKey), findings, bodyHash, skipped);
    await this.context.workspaceState.update(stateKey(documentKey), merged);
    return merged;
  }

  /** 項目1件の対応状態を変更する。永続化済みの状態が無い記事に対しては何もしない。 */
  async setStatus(
    documentKey: string,
    id: string,
    status: ReviewChecklistStatus
  ): Promise<ReviewChecklistDocumentState | undefined> {
    const current = this.get(documentKey);
    if (!current) return undefined;
    const updated: ReviewChecklistDocumentState = {
      ...current,
      items: setChecklistItemStatus(current.items, id, status),
    };
    await this.context.workspaceState.update(stateKey(documentKey), updated);
    return updated;
  }
}
