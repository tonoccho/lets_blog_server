import * as api from './apiClient';
import { SectionContext } from './headingContext';

/**
 * WebviewとExtension間でやり取りするメッセージの型定義。
 *
 * 従来は `{ command: string; [key: string]: unknown }` として受け取り、
 * ハンドラ内で `as unknown as T` へキャストしていたため、コマンド名と
 * ペイロードの対応が型で保証されていなかった。ここで判別可能合併として
 * 定義することで、switch文の網羅性と各分岐でのペイロード型が検査される。
 */

/** すべてのメッセージが持つ判別子。 */
export interface WebviewMessageBase<TCommand extends string> {
  command: TCommand;
}

// --- Article Plan パネル ---

export type PlanInboundMessage =
  | WebviewMessageBase<'loadIssues'>
  | WebviewMessageBase<'loadCategories'>
  | WebviewMessageBase<'openArticle'>
  | (WebviewMessageBase<'sendChat'> & {
      history: api.PlanChatMessage[];
      message: string;
      sessionId?: number;
      issueNumber: number;
    })
  | (WebviewMessageBase<'suggestStructure'> & { history: api.PlanChatMessage[] })
  | (WebviewMessageBase<'acceptStructure'> & { issueNumber: number; structure: string })
  | (WebviewMessageBase<'suggestMetadata'> & { history: api.PlanChatMessage[] })
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'approveAndScaffold'> & {
      issue: api.RepositoryIssue;
      metadata: { title: string; slug: string; categories: string[]; tags: string[] };
    });

export type PlanOutboundCommand =
  | 'cancelled'
  | 'issueList'
  | 'categoryList'
  | 'chatResponse'
  | 'structureSuggestion'
  | 'structureAccepted'
  | 'metadataSuggestion'
  | 'scaffoldCreated'
  | 'error';

// --- Generate Image パネル ---

export type ImageGenInboundMessage =
  | WebviewMessageBase<'loadOptions'>
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'generate'> & { params: api.ImageGenerationParams })
  // 生成画像の実体(base64)はパネル側が保持し、Webviewからは送り返さない。
  // 数MBの文字列をWebview境界で往復させると、その都度コピーが作られるため。
  | WebviewMessageBase<'setAsEyecatch'>
  | WebviewMessageBase<'addAsAsset'>;

export type ImageGenOutboundCommand =
  | 'options'
  | 'generated'
  | 'eyecatchSet'
  | 'assetAdded'
  | 'cancelled'
  | 'error';

// --- Generate Section パネル ---

export type SectionGenInboundMessage =
  | WebviewMessageBase<'init'>
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'generate'> & { params: api.AiSectionParams })
  | (WebviewMessageBase<'insert'> & { text: string });

export type SectionGenOutboundCommand = 'init' | 'generated' | 'inserted' | 'cancelled' | 'error';

/** Generate Section パネルの初期化ペイロード。 */
export interface SectionGenInitPayload {
  articleTitle: string;
  selectedText: string;
  sectionContext: SectionContext;
}
