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

/** 操作の実行者(ログインユーザー)。X-Actor-*ヘッダの送出元でもある。 */
export const ActorSchema = z.object({
  id: z.number(),
  email: z.string(),
  role: z.string(),
});
export type Actor = z.infer<typeof ActorSchema>;

export const LoginResultSchema = z.object({
  user: ActorSchema,
  twoFactorRequired: z.boolean(),
  apiKey: z.string().nullable(),
});
export type LoginResult = z.infer<typeof LoginResultSchema>;

export const PublishResultSchema = z.object({
  wpPostId: z.string(),
  wpPostUrl: z.string(),
  status: z.string(),
});
export type PublishResult = z.infer<typeof PublishResultSchema>;

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

export const AiTagsResultSchema = z.object({
  categories: z.array(z.string()).default([]),
  tags: z.array(z.string()).default([]),
});
export type AiTagsResult = z.infer<typeof AiTagsResultSchema>;

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
});
export type ImageGenerationOptions = z.infer<typeof ImageGenerationOptionsSchema>;

export const SiteSummarySchema = z.object({
  id: z.number(),
  name: z.string(),
  siteKey: z.string(),
});
export type SiteSummary = z.infer<typeof SiteSummarySchema>;
export const SiteSummaryListSchema = z.array(SiteSummarySchema);

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
 */
export const ThemeSkeletonResultSchema = z.object({
  html: z.string().nullish(),
  available: z.boolean(),
  reason: z.string().nullish(),
  eyecatchSpliced: z.boolean(),
});
export type ThemeSkeletonResult = z.infer<typeof ThemeSkeletonResultSchema>;

/** 既存カテゴリ名の一覧。サイト未紐付け時は空配列。 */
export const CategoryNameListSchema = z.array(z.string());

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
