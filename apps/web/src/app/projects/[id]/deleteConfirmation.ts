import type { ProjectEnvironment } from "@/lib/apiClient";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const ENVIRONMENT_ORDER: ProjectEnvironment[] = ["local", "test", "production"];

/**
 * 一括削除(delete-all)の確認文を作る(issue #1181)。
 *
 * 削除は「1つの slug を、それが存在する全環境から消す」取り消せない操作なので、承認の前に
 * 「いくつの、どの環境から消えるのか」を示す。環境の有無は比較表がすでに持つ行データから
 * 求め、`exists` が false の環境(対象外・エラー・未存在)は含めない。追加のAPI呼び出しはしない。
 *
 * 存在する環境が1つも判定できない場合は、件数を断定せず従来どおりの文言にする。
 */
export function buildDeleteConfirmation(
  name: string,
  exists: (environment: ProjectEnvironment) => boolean
): string {
  const present = ENVIRONMENT_ORDER.filter(exists);
  if (present.length === 0) {
    return `「${name}」を、存在するすべての環境から削除します。よろしいですか?`;
  }
  const labels = present.map((environment) => ENVIRONMENT_LABEL[environment]).join("・");
  return `「${name}」を、存在する${present.length}つの環境(${labels})から削除します。よろしいですか?`;
}
