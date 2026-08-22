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
  | (WebviewMessageBase<'loadIssueOutline'> & { issueNumber: number })
  | (WebviewMessageBase<'approveAndScaffold'> & {
      issue: api.RepositoryIssue;
      metadata: { title: string; slug: string; categories: string[]; tags: string[] };
    });

export type PlanOutboundCommand =
  | 'cancelled'
  | 'issueOutline'
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
  | WebviewMessageBase<'addAsAsset'>
  | (WebviewMessageBase<'sendChat'> & { history: api.PlanChatMessage[]; message: string; provider?: string });

export type ImageGenOutboundCommand =
  | 'options'
  | 'generated'
  | 'eyecatchSet'
  | 'assetAdded'
  | 'promptGenerated'
  | 'prefill'
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
  /** letsBlog.aiProviderの現在値(issue #530)。空文字は「サーバー既定値を使用」。 */
  defaultAiProvider: string;
}

// --- Ask AI (Web検索) パネル ---

export type AskAiInboundMessage =
  | WebviewMessageBase<'init'>
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'ask'> & { question: string; provider?: string })
  | (WebviewMessageBase<'insert'> & { text: string });

export type AskAiOutboundCommand = 'init' | 'answered' | 'inserted' | 'cancelled' | 'error';

/** Ask AI パネルの初期化ペイロード。 */
export interface AskAiInitPayload {
  /** letsBlog.aiProviderの現在値(issue #530)。空文字は「サーバー既定値を使用」。 */
  defaultAiProvider: string;
}

// --- Create Article パネル ---

export type ArticleCreationInboundMessage =
  | WebviewMessageBase<'loadProjects'>
  | WebviewMessageBase<'loadPostStatuses'>
  | WebviewMessageBase<'close'>
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'loadCategories'> & { projectId: number })
  | (WebviewMessageBase<'sendChat'> & {
      projectId: number;
      history: api.PlanChatMessage[];
      message: string;
      sessionId?: number;
    })
  | (WebviewMessageBase<'suggestMetadata'> & { projectId: number; history: api.PlanChatMessage[] })
  | (WebviewMessageBase<'suggestStructure'> & { projectId: number; history: api.PlanChatMessage[] })
  | (WebviewMessageBase<'createArticle'> & {
      metadata: {
        projectId: number;
        title: string;
        slug: string;
        categories: string[];
        tags: string[];
        status: string;
      };
      content?: string;
    });

export type ArticleCreationOutboundCommand =
  | 'projectList'
  | 'postStatusList'
  | 'categoryList'
  | 'chatResponse'
  | 'metadataSuggestion'
  | 'structureSuggestion'
  | 'articleCreated'
  | 'cancelled'
  | 'error';

// --- Image Gallery パネル ---

export type ImageGalleryInboundMessage =
  | WebviewMessageBase<'loadImages'>
  | WebviewMessageBase<'cancel'>
  | (WebviewMessageBase<'loadThumbnails'> & { imageIds: number[] })
  | (WebviewMessageBase<'insertImage'> & { imageId: number; prompt?: string })
  | (WebviewMessageBase<'setAsEyecatch'> & { imageId: number })
  | (WebviewMessageBase<'deleteImage'> & { imageId: number })
  | (WebviewMessageBase<'regenerateWithSettings'> & { imageId: number });

export type ImageGalleryOutboundCommand =
  | 'imageList'
  | 'thumbnails'
  | 'imageInserted'
  | 'eyecatchSet'
  | 'imageDeleted'
  | 'deleteCancelled'
  | 'cancelled'
  | 'error';

// --- Diagram Editor パネル ---

export type DiagramEditorInboundMessage =
  | WebviewMessageBase<'ready'>
  | (WebviewMessageBase<'insertNew'> & { name: string; xml: string; svg: string })
  | (WebviewMessageBase<'saveOverwrite'> & { xml: string; svg: string })
  | (WebviewMessageBase<'saveAsNew'> & { name: string; xml: string; svg: string })
  | WebviewMessageBase<'cancel'>;

export type DiagramEditorOutboundCommand =
  | 'init'
  | 'inserted'
  | 'saved'
  | 'cancelled'
  | 'error';

// --- Diagram Gallery パネル ---

export type DiagramGalleryInboundMessage =
  | WebviewMessageBase<'loadDiagrams'>
  | (WebviewMessageBase<'loadThumbnails'> & { diagramIds: number[] })
  | (WebviewMessageBase<'insertDiagram'> & { diagramId: number; name: string })
  | (WebviewMessageBase<'deleteDiagram'> & { diagramId: number })
  | WebviewMessageBase<'cancel'>;

export type DiagramGalleryOutboundCommand =
  | 'diagramList'
  | 'thumbnails'
  | 'diagramInserted'
  | 'diagramDeleted'
  | 'deleteCancelled'
  | 'cancelled'
  | 'error';
