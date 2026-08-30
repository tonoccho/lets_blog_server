"use server";

import { revalidatePath } from "next/cache";
import { requireSession } from "@/lib/session";
import {
  deleteGeneratedImage,
  getGeneratedImage,
  updateGeneratedImageTags,
  type GeneratedImageDetail,
} from "@/lib/apiClient";

/**
 * 生成画像ギャラリーの操作には**ログインを要求する**(issue #824 で追加)。
 *
 * admin 限定にしていないのは、`/image-gallery` が `proxy.ts` の `ADMIN_ONLY_PREFIXES`
 * (`/users` と `/admin`)に含まれず、ログイン済みなら誰でも開ける画面だから。
 * 画面側も `isAdmin` による出し分けをしておらず、一覧・詳細・削除・タグ編集のすべてを
 * ログイン済み利用者に開放している。認可の粒度をそこへ揃える。
 *
 * これは多層防御である。バックエンドの `GeneratedImageController` は認可チェックを
 * 一切持たない(#830)ため、現時点ではここが唯一の関門になっている。
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

/** 自動生成されたタグを手動で編集・追加する(issue #281)。認可は上記参照。 */
export async function updateGeneratedImageTagsAction(id: number, tags: string[]): Promise<GeneratedImageDetail> {
  await requireSession();
  const result = await updateGeneratedImageTags(id, tags);
  revalidatePath("/image-gallery");
  return result;
}
