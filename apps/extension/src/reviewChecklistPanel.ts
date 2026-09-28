import * as vscode from 'vscode';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { groupChecklistItemsByStep, ReviewChecklistItem } from './reviewChecklistLogic';
import { ReviewChecklistInboundMessage, ReviewChecklistOutboundCommand } from './webviewMessages';
import { ReviewChecklistStore } from './reviewChecklistStore';

/**
 * 指摘チェックリストパネル(issue #1216)。レビュー結果をステップ別にまとめたチェックリストとして
 * 別タブに表示し、各項目の対応状態を切り替えられるようにする。永続化はReviewChecklistStoreが担う。
 */
export class ReviewChecklistPanel extends WebviewPanelBase<ReviewChecklistInboundMessage, ReviewChecklistOutboundCommand> {
  /**
   * 開いているパネル。ProofreadController がレビュー再実行後に表示を更新できるよう、
   * 表示中のドキュメントと合わせて保持する(closeされたら破棄時にクリアする)。
   */
  private static current: ReviewChecklistPanel | undefined;

  private documentKey: string | undefined;

  /** チェックリストパネルを開く。既に開いていれば前面に出す。 */
  public static createOrShow(context: vscode.ExtensionContext, store: ReviewChecklistStore): ReviewChecklistPanel {
    return showSingletonPanel('letsBlog.reviewChecklist', () => new ReviewChecklistPanel(context, store));
  }

  /**
   * 表示中のパネルが指定した記事(documentKey)を表示している場合だけ、最新の項目で描画し直す。
   * レビュー実行はパネルを開いていなくても行えるため、開いていない/別記事を見ている場合は何もしない。
   */
  public static refreshIfShowing(documentKey: string, items: ReviewChecklistItem[]): void {
    if (ReviewChecklistPanel.current?.documentKey === documentKey) {
      ReviewChecklistPanel.current.render(items);
    }
  }

  private constructor(context: vscode.ExtensionContext, private readonly store: ReviewChecklistStore) {
    super(context, { viewType: 'letsBlog.reviewChecklist', title: '指摘チェックリスト', assetName: 'reviewChecklist' });
    ReviewChecklistPanel.current = this;
  }

  /** 指定した記事の永続化済みチェックリストを表示する。 */
  public show(documentKey: string): void {
    this.documentKey = documentKey;
    this.render(this.store.get(documentKey)?.items ?? []);
  }

  private render(items: ReviewChecklistItem[]): void {
    this.postMessage('checklist', { groups: groupChecklistItemsByStep(items) });
  }

  protected dispose(): void {
    if (ReviewChecklistPanel.current === this) {
      ReviewChecklistPanel.current = undefined;
    }
    super.dispose();
  }

  protected async handleMessage(message: ReviewChecklistInboundMessage): Promise<void> {
    switch (message.command) {
      case 'setStatus': {
        if (!this.documentKey) return;
        const updated = await this.store.setStatus(this.documentKey, message.id, message.status);
        if (updated) this.render(updated.items);
        return;
      }
    }
  }
}
