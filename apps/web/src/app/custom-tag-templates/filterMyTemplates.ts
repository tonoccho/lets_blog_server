import type { CustomTagTemplate } from "@/lib/apiClient";

/**
 * 「自分のテンプレート」一覧(GET /api/custom-tag-templates/my-templates)へ、
 * 通常一覧と同じ検索語(テンプレート名の部分一致)とカテゴリー(完全一致)を重ねる。
 * このAPIは絞り込みパラメーターを持たないため、画面側で行う。
 */
export function filterMyTemplates(
  templates: CustomTagTemplate[],
  { search, category }: { search?: string; category?: string }
): CustomTagTemplate[] {
  const needle = search?.toLowerCase();
  return templates.filter(
    (t) => (!needle || t.templateName.toLowerCase().includes(needle)) && (!category || t.category === category)
  );
}
