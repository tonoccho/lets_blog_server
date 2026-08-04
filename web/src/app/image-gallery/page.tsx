import { listGeneratedImages } from "@/lib/apiClient";
import { getViewerTimeZone } from "@/lib/session";
import { ImageGalleryGrid } from "./ImageGalleryGrid";

export default async function ImageGalleryPage() {
  const [images, timezone] = await Promise.all([
    listGeneratedImages().catch(() => []),
    getViewerTimeZone(),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">生成画像ギャラリー</h1>

      {images.length === 0 ? (
        <p className="text-neutral-500">生成画像がありません(VSCode拡張で画像を生成すると表示されます)</p>
      ) : (
        <ImageGalleryGrid images={images} timezone={timezone} />
      )}
    </div>
  );
}
