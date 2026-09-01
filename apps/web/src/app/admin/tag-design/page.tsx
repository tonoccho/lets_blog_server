import { getTagDesignSettings } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { TagDesignSettingsPanel } from "../../projects/[id]/tag-design/TagDesignSettingsPanel";

/**
 * プロジェクトに紐付いていないサイト向けの、グローバル既定タグデザインの設定画面(issue #763)。
 *
 * プロジェクト単位の画面(`/projects/[id]/tags` の「組み込みタグのデザイン」タブ)と同じ
 * {@link TagDesignSettingsPanel} を、projectId=null で描画する。サーバー側は
 * `tag_design_settings` の `project_id IS NULL` の行を読み書きする。
 *

 * admin限定。グローバル既定には判定に使えるプロジェクトメンバーシップが無く、
 * 影響範囲も未紐付けサイト全体に及ぶため(API側の GlobalTagDesignSettingController も
 * `requireAdmin()` で同じ判断をしている)。`/admin` 配下に置いているのは、
 * ページ自身の `requireAdminSession()` に加えて proxy.ts の ADMIN_ONLY_PREFIXES でも
 * 弾かれるようにするため。
 */
export default async function GlobalTagDesignPage() {
  await requireAdminSession();

  const overview = await getTagDesignSettings(null);

  return (
    <div className="space-y-6">
      <Breadcrumb items={[{ label: "グローバルタグデザイン" }]} />

      <div className="space-y-2">
        <h1 className="text-xl font-medium">グローバルタグデザイン</h1>
        <p className="max-w-3xl text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトに紐付いていないサイトへ公開するときに使う、<code>[toc]</code> /{" "}
          <code>[blogcard URL]</code> / <code>[amazon URL]</code> 組み込みタグの既定デザインです。
          ここで保存した内容は、プロジェクト未紐付けサイトの次回のプレビュー・公開から反映されます。
        </p>
        <p className="max-w-3xl text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトに紐付いたサイトはこの設定の影響を受けません。プロジェクト側で未設定の場合も
          ここへはフォールバックせず、標準プリセットが使われます。プロジェクトごとの設定は
          各プロジェクトの「タグ」画面から行ってください。
        </p>
      </div>

      <TagDesignSettingsPanel
        projectId={null}
        presets={overview.presets}
        settings={overview.settings}
      />
    </div>
  );
}
