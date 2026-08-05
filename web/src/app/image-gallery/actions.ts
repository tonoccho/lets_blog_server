"use server";

import { revalidatePath } from "next/cache";
import { deleteGeneratedImage, getGeneratedImage, type GeneratedImageDetail } from "@/lib/apiClient";

export async function getGeneratedImageAction(id: number): Promise<GeneratedImageDetail> {
  return getGeneratedImage(id);
}

export async function deleteGeneratedImageAction(id: number): Promise<void> {
  await deleteGeneratedImage(id);
  revalidatePath("/image-gallery");
}
