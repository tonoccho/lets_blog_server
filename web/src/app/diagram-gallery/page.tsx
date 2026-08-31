import { listDiagrams } from "@/lib/apiClient";
import { getViewerTimeZone } from "@/lib/session";
import { DiagramGalleryGrid } from "./DiagramGalleryGrid";

export default async function DiagramGalleryPage() {
  const [diagrams, timezone] = await Promise.all([
    listDiagrams().catch(() => []),
    getViewerTimeZone(),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ダイアグラムギャラリー</h1>

      {diagrams.length === 0 ? (
        <p className="text-neutral-500 dark:text-neutral-400">
          ダイアグラムがありません(VSCode拡張の「Add New Diagram」で作成すると表示されます)
        </p>
      ) : (
        <DiagramGalleryGrid diagrams={diagrams} timezone={timezone} />
      )}
    </div>
  );
}
