"use server";

import { revalidatePath } from "next/cache";
import {
  deleteGeneratedImage,
  getGeneratedImage,
  updateGeneratedImageTags,
  type GeneratedImageDetail,
} from "@/lib/apiClient";

export async function getGeneratedImageAction(id: number): Promise<GeneratedImageDetail> {
  return getGeneratedImage(id);
}

export async function deleteGeneratedImageAction(id: number): Promise<void> {
  await deleteGeneratedImage(id);
  revalidatePath("/image-gallery");
}

/** 自動生成されたタグを手動で編集・追加する(issue #281)。 */
export async function updateGeneratedImageTagsAction(id: number, tags: string[]): Promise<GeneratedImageDetail> {
  const result = await updateGeneratedImageTags(id, tags);
  revalidatePath("/image-gallery");
  return result;
}
