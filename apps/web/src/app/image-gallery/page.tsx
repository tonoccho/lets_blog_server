import { listGeneratedImageFolders, listGeneratedImages } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { ImageGalleryGrid } from "./ImageGalleryGrid";
import { GALLERY_PAGE_SIZE } from "./pageSize";

export default async function ImageGalleryPage() {
  // セッションが更新不能なときはここで /login へリダイレクトする(issue #1234)。
  // 以前はセッションの状態を見ずに描画しており、失敗したlistGeneratedImages()を
  // catch(() => [])で握り潰すため「生成画像がありません」に見えていた。
  await requireSession();
  const [imagesResult, foldersResult, timezone] = await Promise.all([
    loadOrReport("image-gallery", "生成画像", listGeneratedImages(undefined, { limit: GALLERY_PAGE_SIZE, offset: 0 }), []),
    // フォルダ(issue #1493)が取れなくても画像は見せる。失敗は通知で示す。
    loadOrReport("image-gallery", "フォルダ", listGeneratedImageFolders(), []),
    getViewerTimeZone(),
  ]);
  const images = imagesResult.data;

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">画像ギャラリー</h1>

      <FetchErrorNotice labels={failedLabels(imagesResult, foldersResult)} />

      {imagesResult.failed ? null : images.length === 0 ? (
        <p className="text-neutral-500 dark:text-neutral-400">生成画像がありません(VSCode拡張で画像を生成すると表示されます)</p>
      ) : (
        <ImageGalleryGrid images={images} folders={foldersResult.data} timezone={timezone} />
      )}
    </div>
  );
}
