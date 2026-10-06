"use server";

import { revalidatePath } from "next/cache";
import { requireSession } from "@/lib/session";
import { GALLERY_PAGE_SIZE } from "./pageSize";
import {
  bulkDeleteGeneratedImages,
  createGeneratedImageFolder,
  deleteGeneratedImage,
  editGeneratedImage,
  getGeneratedImage,
  listGeneratedImages,
  setGeneratedImageFolder,
  updateGeneratedImageTags,
  type GeneratedImageBulkDeleteResult,
  type GeneratedImageCrop,
  type GeneratedImageDetail,
  type GeneratedImageFolder,
  type GeneratedImageSummary,
} from "@/lib/apiClient";

/**
 * 生成画像ギャラリーの操作には**ログインを要求する**(issue #824 で追加)。
 *
 * admin 限定にしていないのは、`/image-gallery` が `proxy.ts` の `ADMIN_ONLY_PREFIXES`
 * (`/users` と `/admin`)に含まれず、ログイン済みなら誰でも開ける画面だから。
 * 画面側も `isAdmin` による出し分けをしておらず、一覧・詳細・削除・タグ編集のすべてを
 * ログイン済み利用者に開放している。認可の粒度をそこへ揃える。
 *
 * これは多層防御である。バックエンドの `GeneratedImageController` は**認可**チェックを
 * 一切持たない(#830)。ただし media-service の `SecurityConfig` は
 * `anyRequest().authenticated()` なので、**未認証はバックエンドでも弾かれる**。
 * 欠けているのはロール・所有者による絞り込みであって認証ではない。
 *
 * 所有者による絞り込みをしていないのは、`GeneratedImage` に所有者を表す列が無く
 * 現状のスキーマでは表現できないため。ギャラリーもプロジェクト横断で全件を表示している。
 * `projectId` はあるので、将来プロジェクトメンバーシップによる絞り込みは可能。
 */
export async function getGeneratedImageAction(id: number): Promise<GeneratedImageDetail> {
  await requireSession();
  return getGeneratedImage(id);
}

export async function deleteGeneratedImageAction(id: number): Promise<void> {
  await requireSession();
  await deleteGeneratedImage(id);
  revalidatePath("/image-gallery");
}

/**
 * 選んだ生成画像をまとめて削除する(issue #1492)。認可は上記参照(ログイン必須)。
 * 画像ごとの認可は media-service が行い、権限の無い画像を含む要求は403で全体が拒否される。
 */
export async function bulkDeleteGeneratedImagesAction(ids: number[]): Promise<GeneratedImageBulkDeleteResult> {
  await requireSession();
  const result = await bulkDeleteGeneratedImages(ids);
  revalidatePath("/image-gallery");
  return result;
}

/** 自動生成されたタグを手動で編集・追加する(issue #281)。認可は上記参照。 */
export async function updateGeneratedImageTagsAction(id: number, tags: string[]): Promise<GeneratedImageDetail> {
  await requireSession();
  const result = await updateGeneratedImageTags(id, tags);
  revalidatePath("/image-gallery");
  return result;
}

/**
 * フォルダを作成する(issue #1493)。認可は上記参照(ログイン必須)。作成できるのは admin のみで、
 * そうでなければ media-service が403を返し、その理由が呼び出し側へそのまま伝わる。
 */
export async function createGeneratedImageFolderAction(
  name: string,
  parentId: number | null,
): Promise<GeneratedImageFolder> {
  await requireSession();
  const result = await createGeneratedImageFolder(name, parentId);
  revalidatePath("/image-gallery");
  return result;
}

/** 画像の所属フォルダを変える(null は未分類へ戻す)(issue #1493)。認可は上記参照。変更は admin のみ(media-service が判定)。 */
export async function setGeneratedImageFolderAction(
  id: number,
  folderId: number | null,
): Promise<GeneratedImageDetail> {
  await requireSession();
  const result = await setGeneratedImageFolder(id, folderId);
  revalidatePath("/image-gallery");
  return result;
}

/**
 * 画像を回転・反転・切り抜きして、新しい画像として保存する(issue #1655)。認可は上記参照(ログイン必須)。
 * 画像ごとの認可(プロジェクトのメンバーまたは admin)は media-service が判定し、非メンバーは403になる。
 */
export async function editGeneratedImageAction(
  id: number,
  operations: string[],
  crop: GeneratedImageCrop | null,
): Promise<GeneratedImageDetail> {
  await requireSession();
  const result = await editGeneratedImage(id, operations, crop);
  revalidatePath("/image-gallery");
  return result;
}

/**
 * ギャラリーの続き(次の1ページ)を取得する(issue #1472)。認可は上記参照。
 *
 * ページサイズはここで固定し、クライアントから任意の limit を指定させない。
 * `tag` を渡すとサーバ側で絞り込んだ後の一覧の `offset` 位置から返る。
 * `folder` はフォルダid(そのフォルダと子孫の画像)、`"unfiled"`(どのフォルダにも属さない画像)、
 * null(絞り込みなし)(issue #1493)。タグ絞り込みとは併用できる。
 * `source` は種別(`"UPLOAD"` アップロード画像 / `"AI"` AI生成画像)、null は絞り込みなし(issue #1647)。
 */
export async function fetchGalleryImagesPageAction(
  offset: number,
  tag: string | null,
  folder: number | "unfiled" | null,
  source: "UPLOAD" | "AI" | null,
): Promise<GeneratedImageSummary[]> {
  await requireSession();
  return listGeneratedImages(undefined, {
    limit: GALLERY_PAGE_SIZE,
    offset,
    tag: tag ?? undefined,
    folderId: typeof folder === "number" ? folder : undefined,
    unfiled: folder === "unfiled" ? true : undefined,
    source: source ?? undefined,
  });
}
