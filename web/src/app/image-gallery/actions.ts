"use server";

import { getGeneratedImage, type GeneratedImageDetail } from "@/lib/apiClient";

export async function getGeneratedImageAction(id: number): Promise<GeneratedImageDetail> {
  return getGeneratedImage(id);
}
