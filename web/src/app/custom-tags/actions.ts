"use server";

import { generateCustomTag, validateCustomTag, type GenerateCustomTagInput, type CustomTag, type ValidationResult, type ValidateCustomTagRequest } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export async function generateCustomTagAction(
  input: GenerateCustomTagInput
): Promise<{ data?: CustomTag; error?: string }> {
  await requireAdminSession();

  try {
    const result = await generateCustomTag(input);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function validateCustomTagAction(
  input: ValidateCustomTagRequest
): Promise<{ data?: ValidationResult; error?: string }> {
  await requireAdminSession();

  try {
    const result = await validateCustomTag(input);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
