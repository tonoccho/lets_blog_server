import { z } from 'zod';

/**
 * APIレスポンスの検証スキーマ。
 *
 * サーバーのバージョン差異や想定外のレスポンス(HTMLのエラーページ、フィールド欠落など)を
 * そのまま型として信じ込むと、実際に値を使う箇所で初めて実行時エラーになり原因が追いにくい。
 * ここで受信直後に検証し、どのフィールドがどう不正だったかを明示できるようにする。
 *
 * 各レスポンス型はこのスキーマから `z.infer` で導出するため、スキーマと型定義が乖離しない。
 */

/**
 * 操作の実行者(ログインユーザー)。
 * `/api/users` 等サーバーのレスポンスではローカルDBの数値idを含むが、issue #565以降
 * `letsBlog.login` (Device Authorization Grant)で復元するActorはKeycloakのJWTクレーム
 * (`sub`はローカルDBの数値idではなくKeycloakのUUID)から作るため、idは持たない。
 * どちらの由来でも検証できるようoptionalにしている。
 */
export const ActorSchema = z.object({
  id: z.number().optional(),
  email: z.string(),
  role: z.string(),
});
export type Actor = z.infer<typeof ActorSchema>;

export const PublishResultSchema = z.object({
  wpPostId: z.string(),
  wpPostUrl: z.string(),
  status: z.string(),
});
export type PublishResult = z.infer<typeof PublishResultSchema>;

/** サイト+スラッグに対応する既存投稿の照会結果(issue #505)。 */
export const PostLookupResultSchema = z.object({
  wpPostId: z.string(),
  status: z.string(),
});
export type PostLookupResult = z.infer<typeof PostLookupResultSchema>;

export const SourceReferenceSchema = z.object({
  title: z.string(),
  url: z.string(),
});
export type SourceReference = z.infer<typeof SourceReferenceSchema>;

/**
 * AI生成系の共通レスポンス。出典が無い場合にその理由を示すsearchNoteは
 * サーバー側でnullになりうるため、nullableかつ省略可として扱う。
 */
export const AiGenerationResultSchema = z.object({
  result: z.string(),
  sources: z.array(SourceReferenceSchema).default([]),
  searchNote: z.string().nullable().default(null),
});
export type AiDraftResult = z.infer<typeof AiGenerationResultSchema>;
export type AiSectionResult = z.infer<typeof AiGenerationResultSchema>;
/** issue #526: エディタ右クリックメニュー「Ask AI」の質問応答結果。 */
export type AiAskResult = z.infer<typeof AiGenerationResultSchema>;

export const AiTagsResultSchema = z.object({
  categories: z.array(z.string()).default([]),
  tags: z.array(z.string()).default([]),
});
export type AiTagsResult = z.infer<typeof AiTagsResultSchema>;

/** 校正チェックで検出した1件の指摘。POST /api/ai/proofread のレスポンス(issue #523)。 */
export const ProofreadIssueSchema = z.object({
  type: z.enum(['typo', 'readability', 'unnecessary']),
  originalText: z.string(),
  message: z.string(),
  suggestion: z.string().nullable().default(null),
});
export type ProofreadIssue = z.infer<typeof ProofreadIssueSchema>;

export const ProofreadResultSchema = z.object({
  issues: z.array(ProofreadIssueSchema).default([]),
});
export type ProofreadResult = z.infer<typeof ProofreadResultSchema>;

export const AiImageResultSchema = z.object({
  id: z.number(),
  fileName: z.string(),
  dataBase64: z.string(),
  mimeType: z.string(),
});
export type AiImageResult = z.infer<typeof AiImageResultSchema>;

export const AiImageBatchResponseSchema = z.object({
  images: z.array(AiImageResultSchema).min(1),
});
export type AiImageBatchResponse = z.infer<typeof AiImageBatchResponseSchema>;

export const ImageGenerationOptionsSchema = z.object({
  checkpoints: z.array(z.string()).default([]),
  selectedCheckpoint: z.string().default(''),
  samplers: z.array(z.string()).default([]),
  schedulers: z.array(z.string()).default([]),
  loras: z.array(z.string()).default([]),
  defaultWidth: z.number().default(1920),
  defaultHeight: z.number().default(1080),
  defaultNegativePrompt: z.string().nullable().default(null),
  defaultQualityPrompt: z.string().nullable().default(null),
});
export type ImageGenerationOptions = z.infer<typeof ImageGenerationOptionsSchema>;

export const SiteSummarySchema = z.object({
  id: z.number(),
  name: z.string(),
  siteKey: z.string(),
});
export type SiteSummary = z.infer<typeof SiteSummarySchema>;
export const SiteSummaryListSchema = z.array(SiteSummarySchema);

/** 投稿ステータスの選択肢。GET /api/metadata/post-statuses のレスポンス(issue #472)。 */
export const PostStatusOptionSchema = z.object({
  value: z.string(),
  label: z.string(),
});
export type PostStatusOption = z.infer<typeof PostStatusOptionSchema>;
export const PostStatusOptionListSchema = z.array(PostStatusOptionSchema);

/** ロールの表示名。GET /api/metadata/roles のレスポンス(issue #472)。 */
export const RoleOptionSchema = z.object({
  roleName: z.string(),
  displayName: z.string(),
});
export type RoleOption = z.infer<typeof RoleOptionSchema>;
export const RoleOptionListSchema = z.array(RoleOptionSchema);

export const ProjectSummarySchema = z.object({
  id: z.number(),
  name: z.string(),
  slug: z.string(),
  githubRepository: z.string().nullish(),
});
export type ProjectSummary = z.infer<typeof ProjectSummarySchema>;
export const ProjectSummaryListSchema = z.array(ProjectSummarySchema);

export const ProjectSiteSchema = z.object({
  id: z.number(),
  name: z.string(),
  siteKey: z.string(),
});
export type ProjectSite = z.infer<typeof ProjectSiteSchema>;

export const ProjectDetailSchema = z.object({
  id: z.number(),
  name: z.string(),
  slug: z.string(),
  localSite: ProjectSiteSchema.nullable(),
  testSite: ProjectSiteSchema.nullable(),
  productionSite: ProjectSiteSchema.nullable(),
  masterEnvironment: z.string(),
  githubRepository: z.string().nullish(),
});
export type ProjectDetail = z.infer<typeof ProjectDetailSchema>;

export const RepositoryIssueSchema = z.object({
  number: z.number(),
  title: z.string(),
  htmlUrl: z.string(),
  state: z.string(),
  assignees: z.array(z.string()).optional(),
});
export type RepositoryIssue = z.infer<typeof RepositoryIssueSchema>;
export const RepositoryIssueListSchema = z.array(RepositoryIssueSchema);

export const PlanChatResultSchema = z.object({
  reply: z.string(),
  sessionId: z.number(),
});
export type PlanChatResult = z.infer<typeof PlanChatResultSchema>;

export const AiImagePromptResultSchema = z.object({
  prompt: z.string(),
});
export type AiImagePromptResult = z.infer<typeof AiImagePromptResultSchema>;

export const SuggestMetadataResultSchema = z.object({
  titles: z.array(z.string()).default([]),
  slugs: z.array(z.string()).default([]),
  categories: z.array(z.string()).default([]),
  tags: z.array(z.string()).default([]),
});
export type SuggestMetadataResult = z.infer<typeof SuggestMetadataResultSchema>;

export const SuggestStructureResultSchema = z.object({
  structure: z.string(),
});
export type SuggestStructureResult = z.infer<typeof SuggestStructureResultSchema>;

export const AcceptStructureResultSchema = z.object({
  issueNumber: z.number(),
  issueUrl: z.string(),
});
export type AcceptStructureResult = z.infer<typeof AcceptStructureResultSchema>;

export const AssignIssueResultSchema = z.object({
  issueNumber: z.number(),
  htmlUrl: z.string(),
  assignedLogin: z.string(),
});
export type AssignIssueResult = z.infer<typeof AssignIssueResultSchema>;

export const ThemeCssResultSchema = z.object({
  css: z.string(),
  available: z.boolean(),
  reason: z.string().nullish(),
});
export type ThemeCssResult = z.infer<typeof ThemeCssResultSchema>;

/**
 * URLペースト時のカード形式判定・組み込みタグ用情報取得API(/api/content-cache)のレスポンス。
 * dataはtypeに応じてキーが異なる(BLOGCARD: title/description/imageUrl/siteName/url,
 * AMAZON: productName/imageUrl/price/productUrl)。取得できなかった項目はnullになりうる。
 */
export const ContentCacheResultSchema = z.object({
  url: z.string(),
  type: z.enum(['BLOGCARD', 'AMAZON']),
  data: z.record(z.string(), z.string().nullable()),
});
export type ContentCacheResult = z.infer<typeof ContentCacheResultSchema>;

/** 記事構成の取得元となるIssue本文。未設定のIssueでは空文字が返る。 */
export const IssueDescriptionSchema = z.object({
  body: z.string().nullish(),
});

/** プレビュー用にMarkdownから変換されたHTML。 */
export const RenderPreviewResultSchema = z.object({
  html: z.string(),
});

/**
 * サイト内の既存記事ページを骨格として流用し、タイトル/本文/アイキャッチを差し替えた
 * HTML断片(/api/projects/{projectId}/preview/skeleton)。
 * cssは骨格として実際にナビゲートした投稿ページで読み込まれていたスタイルシートを連結したもの。
 * 本文の差し替え位置を特定できずavailableがfalseの場合でも、ナビゲーション自体に成功していれば
 * 含まれることがある(トップページ限定のCSS取得(getThemeCss)では拾えない、is_single()等で
 * 投稿ページ限定で読み込まれるCSSを補うため)。呼び出し側でgetThemeCssの結果とマージすること。
 */
export const ThemeSkeletonResultSchema = z.object({
  html: z.string().nullish(),
  available: z.boolean(),
  reason: z.string().nullish(),
  eyecatchSpliced: z.boolean(),
  css: z.string().nullish(),
  /**
   * ローカル/テスト環境で非公開投稿として実表示した場合の、作成/更新したWordPress投稿ID。
   * 次回のrenderPreviewSkeleton呼び出し時にexistingPreviewPostIdへ渡すことで同じ投稿を更新でき、
   * プレビュー用の投稿を積み上げずに済む。また、プレビュー終了時にこのIDで投稿を削除できる。
   * 従来のスクレイピング&スプライス経路(本番環境等)ではnull。
   */
  previewPostId: z.string().nullish(),
  /**
   * html自体は取得できた(available=true)ものの、アイキャッチのアップロード失敗等
   * 付随処理の一部が失敗した場合の非致命的な警告文。呼び出し側でプレビューへ表示する。
   */
  warning: z.string().nullish(),
});
export type ThemeSkeletonResult = z.infer<typeof ThemeSkeletonResultSchema>;

/** 既存カテゴリ名の一覧。サイト未紐付け時は空配列。 */
export const CategoryNameListSchema = z.array(z.string());

/** 既存タグ名の一覧。サイト未紐付け時は空配列(issue #525)。 */
export const TagNameListSchema = z.array(z.string());

/** 本文中に埋め込めるカスタムタグ(`[tagname]〜[/tagname]`記法)の一覧(issue #522)。 */
export const CustomTagSummarySchema = z.object({
  tagName: z.string(),
  description: z.string().nullable(),
  tagFormat: z.enum(['INLINE', 'BLOCK']),
});
export type CustomTagSummary = z.infer<typeof CustomTagSummarySchema>;
export const CustomTagSummaryListSchema = z.array(CustomTagSummarySchema);

/** 親カテゴリ名付きの既存カテゴリ一覧。子カテゴリ選択時の親カテゴリ自動選択に使う(issue #289)。 */
export const CategoryOptionSchema = z.object({
  name: z.string(),
  parentName: z.string().nullable(),
});
export const CategoryOptionListSchema = z.array(CategoryOptionSchema);
export type CategoryOption = z.infer<typeof CategoryOptionSchema>;

export const ActorListSchema = z.array(ActorSchema);

/**
 * サーバーに保存された生成画像の一覧項目(/api/generated-images)。
 * 画像バイナリは別エンドポイント(/file)から取得する。
 */
export const GeneratedImageSummarySchema = z.object({
  id: z.number(),
  projectId: z.number().nullish(),
  prompt: z.string().nullish(),
  checkpoint: z.string().nullish(),
  createdAt: z.string().nullish(),
});
export type GeneratedImageSummary = z.infer<typeof GeneratedImageSummarySchema>;
export const GeneratedImageSummaryListSchema = z.array(GeneratedImageSummarySchema);

/**
 * 生成画像の詳細(/api/generated-images/{id})。生成に使ったパラメータ一式を含む。
 * Image Galleryの右クリックメニューから、その設定でGenerate Imageを開くために使う(issue #294)。
 */
export const GeneratedImageDetailSchema = z.object({
  id: z.number(),
  projectId: z.number().nullish(),
  prompt: z.string().nullish(),
  negativePrompt: z.string().nullish(),
  steps: z.number().nullish(),
  cfgScale: z.number().nullish(),
  samplerName: z.string().nullish(),
  scheduler: z.string().nullish(),
  seed: z.number().nullish(),
  width: z.number().nullish(),
  height: z.number().nullish(),
  batchSize: z.number().nullish(),
  // バッチ内の位置(0起点、issue #1101)。#1101以前に生成した画像はnull。
  batchIndex: z.number().nullish(),
  checkpoint: z.string().nullish(),
  loraName: z.string().nullish(),
  loraWeight: z.number().nullish(),
  createdAt: z.string().nullish(),
});
export type GeneratedImageDetail = z.infer<typeof GeneratedImageDetailSchema>;

export const DiagramSummarySchema = z.object({
  id: z.number(),
  projectId: z.number().nullish(),
  name: z.string(),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type DiagramSummary = z.infer<typeof DiagramSummarySchema>;
export const DiagramSummaryListSchema = z.array(DiagramSummarySchema);

export const DiagramDetailSchema = DiagramSummarySchema.extend({
  xml: z.string(),
  svg: z.string(),
});
export type DiagramDetail = z.infer<typeof DiagramDetailSchema>;
