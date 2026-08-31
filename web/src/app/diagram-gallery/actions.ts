"use server";

import { revalidatePath } from "next/cache";
import { deleteDiagram, getDiagram, type DiagramDetail } from "@/lib/apiClient";

export async function getDiagramAction(id: number): Promise<DiagramDetail> {
  return getDiagram(id);
}

export async function deleteDiagramAction(id: number): Promise<void> {
  await deleteDiagram(id);
  revalidatePath("/diagram-gallery");
}
