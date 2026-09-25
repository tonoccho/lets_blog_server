import { listGeneratedImages } from "@/lib/apiClient";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { ImageGalleryGrid } from "./ImageGalleryGrid";

export default async function ImageGalleryPage() {
  // セッションが更新不能なときはここで /login へリダイレクトする(issue #1234)。
  // 以前はセッションの状態を見ずに描画しており、失敗したlistGeneratedImages()を
  // catch(() => [])で握り潰すため「生成画像がありません」に見えていた。
  await requireSession();
  const [images, timezone] = await Promise.all([
    listGeneratedImages().catch(() => []),
    getViewerTimeZone(),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">生成画像ギャラリー</h1>

      {images.length === 0 ? (
        <p className="text-neutral-500 dark:text-neutral-400">生成画像がありません(VSCode拡張で画像を生成すると表示されます)</p>
      ) : (
        <ImageGalleryGrid images={images} timezone={timezone} />
      )}
    </div>
  );
}
