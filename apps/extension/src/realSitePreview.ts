import * as vscode from 'vscode';
import { Actor, createSignedPreviewUrl } from './apiClient';
import { PreviewMessage, PreviewPanel, SiteOption } from './previewPanel';

/** {@link showRealSitePreview}が必要とするサイトの選択肢(拡張のプレビュー対象サイト)。 */
export interface RealSitePreviewSite {
  label: string;
  siteId?: number;
  siteName: string;
}

export interface RealSitePreviewParams {
  context: vscode.ExtensionContext;
  apiKey: string;
  actor: Actor | undefined;
  projectId: number;
  site: RealSitePreviewSite;
  availableSites: SiteOption[];
  onMessage: (message: PreviewMessage) => void;
  /** 変換済みの本文HTML。 */
  html: string;
  title: string;
  categories?: string[];
  tags?: string[];
  featuredImageDataUri?: string;
  /** 進捗の表示。 */
  report: (message: string) => void;
}

/**
 * サイトが紐づいている場合に、実サイトの署名付きプレビューURL(issue #1562)で表示する。
 * プラグインが使えないサイトでは旧方式へ落とさず導入の案内を出す。
 *
 * @returns 表示(または案内)を行ったらtrue。サイトが未紐付けで何もしなかったらfalse(旧経路は#1564で削除した。呼び出し側はサイトが無いとき案内を出す)。
 */
export async function showRealSitePreview(params: RealSitePreviewParams): Promise<boolean> {
  const { site } = params;
  if (site.siteId == null) return false;

  params.report(`${site.siteName} のプレビューURLを取得しています…`);
  const signed = await createSignedPreviewUrl(params.apiKey, params.actor, params.projectId, {
    siteId: site.siteId,
    title: params.title || '(無題)',
    contentHtml: params.html,
    categories: params.categories,
    tags: params.tags,
    featuredImageDataUri: params.featuredImageDataUri,
  });
  const common = {
    siteLabel: `${site.label} / ${site.siteName}`,
    onMessage: params.onMessage,
    availableSites: params.availableSites,
    currentSiteId: site.siteId,
  };
  if (signed.kind === 'ready') {
    PreviewPanel.showRealSite(params.context, { ...common, url: signed.url });
  } else {
    PreviewPanel.showPluginGuidance(params.context, {
      ...common,
      needsUpdate: signed.needsUpdate,
      message: signed.message,
    });
  }
  return true;
}
