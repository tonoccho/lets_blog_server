"use client";

import { useEffect, useMemo, useRef, useState, useTransition } from "react";
import { Sparkles, Upload } from "lucide-react";
import type { GeneratedImageDetail, GeneratedImageFolder, GeneratedImageSummary } from "@/lib/apiClient";
import { formatDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import {
  bulkDeleteGeneratedImagesAction,
  createGeneratedImageFolderAction,
  deleteGeneratedImageAction,
  fetchGalleryImagesPageAction,
  getGeneratedImageAction,
  setGeneratedImageFolderAction,
  updateGeneratedImageTagsAction,
} from "./actions";
import { ImageEditDialog } from "./ImageEditDialog";
import { GALLERY_PAGE_SIZE } from "./pageSize";

const PROVIDER_LABEL: Record<string, string> = {
  COMFYUI: "ComfyUI",
  CHATGPT: "ChatGPT",
  UPLOAD: "アップロード",
};

/** アップロード画像(provider=UPLOAD、issue #1599)はpromptを持たないので、代わりに出所を表す名前で呼ぶ。 */
const UPLOADED_IMAGE_LABEL = "アップロード画像";

/** カードの種別アイコンの名前(issue #1646)。UPLOAD 以外はすべて AI 生成で、ComfyUI / ChatGPT は名前で区別する。 */
const AI_SOURCE_LABEL: Record<string, string> = {
  COMFYUI: "AI生成(ComfyUI)",
  CHATGPT: "AI生成(ChatGPT)",
};

function sourceIconLabel(provider: string): string {
  if (provider === "UPLOAD") return PROVIDER_LABEL.UPLOAD;
  return AI_SOURCE_LABEL[provider] ?? "AI生成";
}

function imageLabel(prompt: string | null): string {
  return prompt ?? UPLOADED_IMAGE_LABEL;
}

/** id で重複を除いて末尾へ追加する。offset 取得中に画像が作られて境界がずれても同じ画像を2度出さない(issue #1472)。 */
function appendUnique(current: GeneratedImageSummary[], incoming: GeneratedImageSummary[]): GeneratedImageSummary[] {
  const seen = new Set(current.map((image) => image.id));
  return [...current, ...incoming.filter((image) => !seen.has(image.id))];
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** フォルダでの絞り込み。フォルダid(そのフォルダと子孫)、"unfiled"(未分類)、null(絞り込みなし)(issue #1493)。 */
type FolderFilter = number | "unfiled" | null;
/** 種別の絞り込み(issue #1647)。UPLOAD はアップロード画像、AI は ComfyUI / ChatGPT の画像、null は絞り込みなし。 */
type SourceFilter = "UPLOAD" | "AI" | null;

const SOURCE_CHIPS: { label: string; value: SourceFilter }[] = [
  { label: "すべて", value: null },
  { label: "アップロード", value: "UPLOAD" },
  { label: "AI生成", value: "AI" },
];

/** 親idごとの子フォルダ(作成順)。ツリー表示と選択肢の元になる。 */
function groupByParent(folders: GeneratedImageFolder[]): Map<number | null, GeneratedImageFolder[]> {
  const byParent = new Map<number | null, GeneratedImageFolder[]>();
  for (const folder of folders) {
    byParent.set(folder.parentId, [...(byParent.get(folder.parentId) ?? []), folder]);
  }
  return byParent;
}

/** ツリーを深さ優先で平らにする。`<select>` の選択肢を階層順に並べるのに使う。 */
function flattenFolders(
  byParent: Map<number | null, GeneratedImageFolder[]>,
  parentId: number | null = null,
  depth = 0,
): { folder: GeneratedImageFolder; depth: number }[] {
  return (byParent.get(parentId) ?? []).flatMap((folder) => [
    { folder, depth },
    ...flattenFolders(byParent, folder.id, depth + 1),
  ]);
}

/**
 * 生成画像の一覧。`images` は最初の1ページ(issue #1472)。末尾が画面に近づくと次のページを
 * Server Action で取得して追加する(無限スクロール)。タグの絞り込みはサーバ側で行い、
 * 選ぶたびに offset=0 から取り直す。
 */
function folderButtonClass(selected: boolean): string {
  return selected
    ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
    : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400";
}

export function ImageGalleryGrid({
  images,
  folders: initialFolders = [],
  timezone,
}: {
  images: GeneratedImageSummary[];
  /** 共通のフォルダツリー(フラット。親子は parentId)(issue #1493)。 */
  folders?: GeneratedImageFolder[];
  timezone: string | null;
}) {
  /** 表示中の一覧(絞り込み中は絞り込み後の一覧)。 */
  const [items, setItems] = useState<GeneratedImageSummary[]>(images);
  /** 絞り込みなしで読み込み済みの画像。タグのチップの元になる(Requirements 10)。 */
  const [knownImages, setKnownImages] = useState<GeneratedImageSummary[]>(images);
  /** 表示中の一覧のサーバ側での取得済み件数(重複除去前)。次の offset になる。 */
  const [fetchedCount, setFetchedCount] = useState(images.length);
  const [hasMore, setHasMore] = useState(images.length >= GALLERY_PAGE_SIZE);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<{ label: string; message: string; retry: () => void } | null>(null);
  /** 最後に発行した取得の番号。古い応答(タグを切り替える前の続き)を捨てるのに使う。 */
  const requestSeq = useRef(0);
  const loadingRef = useRef(false);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<GeneratedImageDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();
  const [isDeleting, startDeleteTransition] = useTransition();
  const [isSavingTags, startTagsTransition] = useTransition();
  const [newTag, setNewTag] = useState("");
  /** 一括削除のために選んだ画像のid(issue #1492)。表示中の画像だけが対象。 */
  const [checkedIds, setCheckedIds] = useState<Set<number>>(new Set());
  // Shift+クリックの範囲選択の起点。最後に Shift なしで選択を切り替えた画像(issue #1615)。
  const anchorId = useRef<number | null>(null);
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [bulkResult, setBulkResult] = useState<{ type: "success" | "error"; text: string } | null>(null);
  /** タグ一覧を絞り込むフィルタ(issue #281)。nullは絞り込みなし。 */
  const [activeTag, setActiveTag] = useState<string | null>(null);
  /** フォルダで絞り込むフィルタ(issue #1493)。タグ絞り込みと併用できる。 */
  const [activeFolder, setActiveFolder] = useState<FolderFilter>(null);
  /** 種別で絞り込むフィルタ(issue #1647)。タグ・フォルダと併用できる。 */
  const [activeSource, setActiveSource] = useState<SourceFilter>(null);
  const [folders, setFolders] = useState<GeneratedImageFolder[]>(initialFolders);
  /** 折りたたんだフォルダのid。既定はすべて展開。 */
  const [collapsedIds, setCollapsedIds] = useState<Set<number>>(new Set());
  const [newFolderName, setNewFolderName] = useState("");
  const [newFolderParent, setNewFolderParent] = useState("");
  const [creatingFolder, setCreatingFolder] = useState(false);
  const [folderError, setFolderError] = useState<string | null>(null);
  const [isSavingFolder, startFolderTransition] = useTransition();
  /** 「この画像の設定をコピー」ボタンの一時的なフィードバック表示(issue #437)。 */
  const [settingsCopied, setSettingsCopied] = useState(false);
  /** 編集画面を開いているか(issue #1655)。 */
  const [editing, setEditing] = useState(false);
  /** 編集結果を新しい画像として保存した旨の表示(issue #1655)。詳細を閉じると消える。 */
  const [editSavedMessage, setEditSavedMessage] = useState<string | null>(null);
  // 個人設定TZが未設定のときだけ使う(mounted前後でサーバー/クライアントの出力を
  // 一致させるため、issue #1362と同じ形。issue #1363)。個人設定TZがあるときはSSR/
  // クライアントで常に同じ文字列になるためこのフラグを見ない。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  const allChecked = items.length > 0 && items.every((image) => checkedIds.has(image.id));

  const allTags = useMemo(() => {
    const set = new Set<string>();
    knownImages.forEach((image) => (image.tags || []).forEach((tag) => set.add(tag)));
    return Array.from(set).sort();
  }, [knownImages]);

  const foldersByParent = useMemo(() => groupByParent(folders), [folders]);
  const folderOptions = useMemo(() => flattenFolders(foldersByParent), [foldersByParent]);

  /** 現在の一覧の続きを取得して末尾に追加する。 */
  function loadMore() {
    if (loadingRef.current || !hasMore) return;
    const seq = ++requestSeq.current;
    const tag = activeTag;
    const folder = activeFolder;
    const source = activeSource;
    loadingRef.current = true;
    setLoading(true);
    setLoadError(null);
    fetchGalleryImagesPageAction(fetchedCount, tag, folder, source).then(
      (next) => {
        if (seq !== requestSeq.current) return;
        loadingRef.current = false;
        setItems((current) => appendUnique(current, next));
        if (tag === null && folder === null && source === null) setKnownImages((current) => appendUnique(current, next));
        setFetchedCount((count) => count + next.length);
        setHasMore(next.length >= GALLERY_PAGE_SIZE);
        setLoading(false);
      },
      (err) => {
        if (seq !== requestSeq.current) return;
        loadingRef.current = false;
        setLoadError({ label: "続きを読み込めませんでした", message: errorMessage(err), retry: loadMore });
        setLoading(false);
      },
    );
  }

  /**
   * タグ(nullは「すべて」)・フォルダ・種別の絞り込みを選び直し、offset=0 から取り直す。
   * 失敗したときは読み込み済みの一覧と現在の絞り込みを残す。
   */
  function applyFilter(tag: string | null, folder: FolderFilter, source: SourceFilter) {
    const seq = ++requestSeq.current;
    loadingRef.current = true;
    setLoading(true);
    setLoadError(null);
    fetchGalleryImagesPageAction(0, tag, folder, source).then(
      (first) => {
        if (seq !== requestSeq.current) return;
        loadingRef.current = false;
        setActiveTag(tag);
        setActiveFolder(folder);
        setActiveSource(source);
        setCheckedIds(new Set());
        anchorId.current = null;
        setItems(first);
        if (tag === null && folder === null && source === null) setKnownImages(first);
        setFetchedCount(first.length);
        setHasMore(first.length >= GALLERY_PAGE_SIZE);
        setLoading(false);
      },
      (err) => {
        if (seq !== requestSeq.current) return;
        loadingRef.current = false;
        setLoadError({
          label: "絞り込みを読み込めませんでした",
          message: errorMessage(err),
          retry: () => applyFilter(tag, folder, source),
        });
        setLoading(false);
      },
    );
  }

  function selectTag(tag: string | null) {
    applyFilter(tag, activeFolder, activeSource);
  }

  function selectFolder(folder: FolderFilter) {
    applyFilter(activeTag, folder, activeSource);
  }

  function selectSource(source: SourceFilter) {
    applyFilter(activeTag, activeFolder, source);
  }

  function toggleCollapsed(id: number) {
    setCollapsedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /** フォルダを作成する(admin のみ。権限が無ければ media-service の403の理由をそのまま表示する)。 */
  async function handleCreateFolder() {
    setCreatingFolder(true);
    setFolderError(null);
    try {
      const created = await createGeneratedImageFolderAction(
        newFolderName.trim(),
        newFolderParent === "" ? null : Number(newFolderParent),
      );
      setFolders((current) => [...current, created]);
      setNewFolderName("");
    } catch (err) {
      setFolderError(errorMessage(err));
    } finally {
      setCreatingFolder(false);
    }
  }

  /** 詳細の画像の所属フォルダを変える(空文字は未分類)。絞り込み中なら一覧を同じ条件で取り直す。 */
  function handleFolderChange(imageId: number, value: string) {
    const folderId = value === "" ? null : Number(value);
    startFolderTransition(async () => {
      try {
        const result = await setGeneratedImageFolderAction(imageId, folderId);
        setDetail(result);
        const apply = (list: GeneratedImageSummary[]) =>
          list.map((image) => (image.id === imageId ? { ...image, folderId: result.folderId } : image));
        setItems(apply);
        setKnownImages(apply);
        if (activeFolder !== null) applyFilter(activeTag, activeFolder, activeSource);
      } catch (err) {
        setError(errorMessage(err));
      }
    });
  }

  function renderFolderNodes(parentId: number | null, depth: number) {
    return (foldersByParent.get(parentId) ?? []).map((folder) => {
      const hasChildren = foldersByParent.has(folder.id);
      const collapsed = collapsedIds.has(folder.id);
      return (
        <li
          key={folder.id}
          role="treeitem"
          aria-label={folder.name}
          aria-level={depth}
          aria-selected={activeFolder === folder.id}
          aria-expanded={hasChildren ? !collapsed : undefined}
        >
          <div className="flex items-center gap-1">
            {hasChildren ? (
              <button
                type="button"
                onClick={() => toggleCollapsed(folder.id)}
                aria-label={`「${folder.name}」を${collapsed ? "展開" : "折りたたむ"}`}
                className="w-4 text-neutral-500"
              >
                {collapsed ? "▸" : "▾"}
              </button>
            ) : (
              <span className="w-4" aria-hidden="true" />
            )}
            <button
              type="button"
              onClick={() => selectFolder(folder.id)}
              className={`rounded px-2 py-0.5 ${folderButtonClass(activeFolder === folder.id)}`}
            >
              {folder.name}
            </button>
          </div>
          {hasChildren && !collapsed && (
            <ul role="group" className="ml-4 space-y-1">
              {renderFolderNodes(folder.id, depth + 1)}
            </ul>
          )}
        </li>
      );
    });
  }

  // 最新の loadMore を observer から呼ぶ(observer は state が変わるたびに作り直すので古い閉包を掴まない)。
  const loadMoreRef = useRef(loadMore);
  useEffect(() => {
    loadMoreRef.current = loadMore;
  });

  // 一覧の末尾が画面に近づいたら続きを読む。observer は件数・読み込み状態が変わるたびに作り直すので、
  // 追加した後も末尾がまだ画面内なら、作り直した直後の通知でさらに続きを読む。
  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore || loading || loadError) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMoreRef.current();
      },
      { rootMargin: "400px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasMore, loading, loadError, items.length]);

  function openDetail(id: number) {
    setSelectedId(id);
    setDetail(null);
    setError(null);
    setEditing(false);
    setEditSavedMessage(null);
    setNewTag("");
    startTransition(async () => {
      try {
        const result = await getGeneratedImageAction(id);
        setDetail(result);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function closeDetail() {
    setSelectedId(null);
    setDetail(null);
    setError(null);
    setEditing(false);
    setEditSavedMessage(null);
  }

  /** 編集結果が新しい画像として保存された。一覧は今の絞り込みのまま取り直す(新しい画像が先頭に現れる)。 */
  function handleEditSaved(saved: GeneratedImageDetail) {
    setEditing(false);
    setEditSavedMessage(`新しい画像として保存しました(ID ${saved.id})`);
    applyFilter(activeTag, activeFolder, activeSource);
  }

  function handleDelete(id: number) {
    if (!window.confirm("この生成画像を削除しますか?この操作は取り消せません。")) {
      return;
    }
    startDeleteTransition(async () => {
      try {
        await deleteGeneratedImageAction(id);
        // 一覧はローカルの状態なので、サーバ側で消した画像をここでも取り除く(issue #1472)。
        // 表示中の一覧から消えた分だけ、次の offset を戻す。
        if (items.some((image) => image.id === id)) setFetchedCount((count) => count - 1);
        setItems((current) => current.filter((image) => image.id !== id));
        setKnownImages((current) => current.filter((image) => image.id !== id));
        // 選択中だった画像を単体削除したら選択からも外す。残すと次の一括削除が存在しない id を送る。
        setCheckedIds((current) => {
          if (!current.has(id)) return current;
          const next = new Set(current);
          next.delete(id);
          return next;
        });
        closeDetail();
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function toggleChecked(id: number) {
    setCheckedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  /**
   * 画像のクリックによる選択。Shift が押されていて起点が表示中にあれば、起点から表示順に範囲を起点の状態へ揃える。
   * それ以外は 1 枚を切り替えて起点にする。起点が一覧から消えていれば起点なしとして扱う。
   */
  function handleSelectClick(id: number, shiftKey: boolean) {
    const anchorIndex = items.findIndex((image) => image.id === anchorId.current);
    if (shiftKey && anchorIndex >= 0) {
      const targetIndex = items.findIndex((image) => image.id === id);
      const select = checkedIds.has(items[anchorIndex].id);
      const rangeIds = items
        .slice(Math.min(anchorIndex, targetIndex), Math.max(anchorIndex, targetIndex) + 1)
        .map((image) => image.id);
      setCheckedIds((current) => {
        const next = new Set(current);
        rangeIds.forEach((rangeId) => (select ? next.add(rangeId) : next.delete(rangeId)));
        return next;
      });
      return;
    }
    anchorId.current = id;
    toggleChecked(id);
  }

  /** Shift+クリックでブラウザの文字選択が始まらないようにする。 */
  function preventShiftTextSelection(e: React.MouseEvent) {
    if (e.shiftKey) e.preventDefault();
  }

  /** 表示中の画像がすべて選択済みなら全て外し、そうでなければ表示中の全画像を選ぶ。 */
  function toggleCheckAll() {
    setCheckedIds(allChecked ? new Set() : new Set(items.map((image) => image.id)));
  }

  async function handleBulkDelete() {
    if (checkedIds.size === 0 || bulkDeleting) return;
    if (!window.confirm(`${checkedIds.size}件の生成画像を削除します。この操作は元に戻せません。よろしいですか?`)) {
      return;
    }
    setBulkDeleting(true);
    setBulkResult(null);
    try {
      const result = await bulkDeleteGeneratedImagesAction(Array.from(checkedIds));
      const deleted = new Set(result.deletedIds);
      // 表示中の一覧から消えた分だけ、次の offset を戻す(単体削除と同じ扱い)。
      const removedFromItems = items.filter((image) => deleted.has(image.id)).length;
      setFetchedCount((count) => count - removedFromItems);
      setItems((current) => current.filter((image) => !deleted.has(image.id)));
      setKnownImages((current) => current.filter((image) => !deleted.has(image.id)));
      setCheckedIds((current) => new Set(Array.from(current).filter((id) => !deleted.has(id))));
      setBulkResult({
        type: "success",
        text:
          result.failedCount > 0
            ? `${result.deletedCount}件を削除しました。${result.failedCount}件の削除に失敗しました`
            : `${result.deletedCount}件を削除しました`,
      });
    } catch (err) {
      setBulkResult({ type: "error", text: errorMessage(err) });
    } finally {
      setBulkDeleting(false);
    }
  }

  function saveTags(tags: string[]) {
    if (selectedId === null) return;
    startTagsTransition(async () => {
      try {
        const result = await updateGeneratedImageTagsAction(selectedId, tags);
        setDetail(result);
        // 一覧の画像のタグとチップにも反映する(issue #1472)。
        const apply = (list: GeneratedImageSummary[]) =>
          list.map((image) => (image.id === selectedId ? { ...image, tags: result.tags } : image));
        setItems(apply);
        setKnownImages(apply);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function handleAddTag() {
    const trimmed = newTag.trim();
    if (!trimmed || !detail) return;
    if (detail.tags.includes(trimmed)) {
      setNewTag("");
      return;
    }
    saveTags([...detail.tags, trimmed]);
    setNewTag("");
  }

  function handleRemoveTag(tag: string) {
    if (!detail) return;
    saveTags(detail.tags.filter((t) => t !== tag));
  }

  /** 生成パラメータをJSON形式でクリップボードにコピーする(issue #437)。アセット画像生成の「クリップボードから作成」で貼り付けられる。 */
  async function handleCopySettings() {
    if (!detail) return;
    const settings = {
      prompt: detail.prompt,
      negativePrompt: detail.negativePrompt,
      steps: detail.steps,
      cfgScale: detail.cfgScale,
      samplerName: detail.samplerName,
      scheduler: detail.scheduler,
      seed: detail.seed,
      width: detail.width,
      height: detail.height,
      batchSize: detail.batchSize,
      checkpoint: detail.checkpoint,
      loraName: detail.loraName,
      loraWeight: detail.loraWeight,
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(settings));
      setSettingsCopied(true);
      setTimeout(() => setSettingsCopied(false), 2000);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-2 text-xs">
        {folders.length > 0 && (
          <div className="space-y-1">
            <span className="text-neutral-500 dark:text-neutral-400">フォルダで絞り込み:</span>
            <ul role="tree" aria-label="フォルダツリー" className="space-y-1">
              {[
                { label: "すべて", value: null },
                { label: "未分類", value: "unfiled" as const },
              ].map(({ label, value }) => (
                <li key={label} role="treeitem" aria-label={label} aria-level={1} aria-selected={activeFolder === value}>
                  <button
                    type="button"
                    onClick={() => selectFolder(value)}
                    className={`ml-5 rounded px-2 py-0.5 ${folderButtonClass(activeFolder === value)}`}
                  >
                    {label}
                  </button>
                </li>
              ))}
              {renderFolderNodes(null, 1)}
            </ul>
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="text"
            aria-label="新しいフォルダの名前"
            value={newFolderName}
            onChange={(e) => setNewFolderName(e.target.value)}
            placeholder="新しいフォルダ"
            disabled={creatingFolder}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1"
          />
          <select
            aria-label="親フォルダ"
            value={newFolderParent}
            onChange={(e) => setNewFolderParent(e.target.value)}
            disabled={creatingFolder}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1"
          >
            <option value="">(最上位)</option>
            {folderOptions.map(({ folder, depth }) => (
              <option key={folder.id} value={folder.id}>
                {"— ".repeat(depth)}
                {folder.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={handleCreateFolder}
            disabled={creatingFolder || !newFolderName.trim()}
            className="rounded bg-neutral-900 px-3 py-1 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {creatingFolder ? "作成中…" : "フォルダを作成"}
          </button>
        </div>
        {folderError && (
          <p role="alert" className="text-red-600">
            {folderError}
          </p>
        )}
      </div>

      <div role="group" aria-label="種別で絞り込み" className="flex flex-wrap items-center gap-2 text-xs">
        <span className="text-neutral-500 dark:text-neutral-400">種別で絞り込み:</span>
        {SOURCE_CHIPS.map((chip) => (
          <button
            key={chip.label}
            type="button"
            onClick={() => selectSource(chip.value)}
            className={`rounded-full px-2.5 py-1 ${
              activeSource === chip.value
                ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
            }`}
          >
            {chip.label}
          </button>
        ))}
      </div>

      {allTags.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-neutral-500 dark:text-neutral-400">タグで絞り込み:</span>
          <button
            type="button"
            onClick={() => selectTag(null)}
            className={`rounded-full px-2.5 py-1 ${
              activeTag === null
                ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
            }`}
          >
            すべて
          </button>
          {allTags.map((tag) => (
            <button
              key={tag}
              type="button"
              onClick={() => selectTag(tag === activeTag ? null : tag)}
              className={`rounded-full px-2.5 py-1 ${
                tag === activeTag
                  ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                  : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
              }`}
            >
              {tag}
            </button>
          ))}
        </div>
      )}

      {items.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <button type="button" onClick={toggleCheckAll} className="underline">
            {allChecked ? "全選択解除" : "全選択"}
          </button>
          <span className="text-neutral-500 dark:text-neutral-400">{checkedIds.size}件選択中</span>
          <button
            type="button"
            onClick={handleBulkDelete}
            disabled={checkedIds.size === 0 || bulkDeleting}
            className="rounded bg-red-600 px-3 py-1 text-white hover:bg-red-700 disabled:opacity-50"
          >
            {bulkDeleting ? "削除中…" : `選択した${checkedIds.size}件を削除`}
          </button>
        </div>
      )}
      {bulkResult && (
        <p
          role={bulkResult.type === "error" ? "alert" : "status"}
          className={bulkResult.type === "error" ? "text-sm text-red-600" : "text-sm text-neutral-700 dark:text-neutral-300"}
        >
          {bulkResult.text}
        </p>
      )}

      {items.length === 0 ? (
        <p className="text-neutral-500 dark:text-neutral-400">該当する画像がありません。</p>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {items.map((image) => (
            <div key={image.id} className="relative">
              <input
                type="checkbox"
                aria-label={`${imageLabel(image.prompt)}を選択`}
                checked={checkedIds.has(image.id)}
                onChange={() => {}}
                onClick={(e) => handleSelectClick(image.id, e.shiftKey)}
                onMouseDown={preventShiftTextSelection}
                className="absolute left-2 top-2 z-10 h-5 w-5"
              />
              <button
                type="button"
                aria-label={`${imageLabel(image.prompt)}の詳細を表示`}
                onClick={() => openDetail(image.id)}
                className="absolute right-2 top-2 z-10 rounded-full bg-white/90 p-1 text-neutral-700 shadow hover:bg-white dark:bg-neutral-900/90 dark:text-neutral-200 dark:hover:bg-neutral-900"
              >
                <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4">
                  <circle cx="8.5" cy="8.5" r="5.5" />
                  <path d="M12.5 12.5L18 18" strokeLinecap="round" />
                </svg>
              </button>
              {/* カード本体のクリックは選択の切り替え。チェックボックスと同じ handleSelectClick を通す(issue #1614)。 */}
              <div
                onClick={(e) => handleSelectClick(image.id, e.shiftKey)}
                onMouseDown={preventShiftTextSelection}
                className="group w-full cursor-pointer overflow-hidden rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-left"
              >
                <img
                  src={`/image-gallery/${image.id}/file`}
                  alt={imageLabel(image.prompt)}
                  className="aspect-square w-full bg-neutral-100 object-contain group-hover:opacity-80 dark:bg-neutral-800"
                />
                <div className="space-y-1 p-2 text-xs">
                  <div className="flex items-start gap-1">
                    <span
                      role="img"
                      aria-label={sourceIconLabel(image.provider)}
                      title={sourceIconLabel(image.provider)}
                      className="mt-0.5 shrink-0 text-neutral-500 dark:text-neutral-400"
                    >
                      {image.provider === "UPLOAD" ? (
                        <Upload aria-hidden="true" className="h-3.5 w-3.5" />
                      ) : (
                        <Sparkles aria-hidden="true" className="h-3.5 w-3.5" />
                      )}
                    </span>
                    <p className="line-clamp-2 text-neutral-700 dark:text-neutral-300">{imageLabel(image.prompt)}</p>
                  </div>
                  {image.tags && image.tags.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {image.tags.map((tag) => (
                        <button
                          key={tag}
                          type="button"
                          aria-label={`タグ「${tag}」で絞り込む`}
                          onClick={(e) => {
                            // カード本体のクリック(選択の切り替え)に伝えない。issue #1616
                            e.stopPropagation();
                            applyFilter(tag, activeFolder, activeSource);
                          }}
                          className="rounded-full bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-neutral-600 dark:text-neutral-400 hover:bg-neutral-200 dark:hover:bg-neutral-700"
                        >
                          {tag}
                        </button>
                      ))}
                    </div>
                  )}
                  <p className="text-neutral-400">
                    {timezone
                      ? formatDateTime(image.createdAt, timezone)
                      : mounted
                        ? formatDateTime(image.createdAt)
                        : TIMEZONE_PENDING_PLACEHOLDER}
                  </p>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {hasMore && <div ref={sentinelRef} aria-hidden="true" className="h-1" />}
      {loading && <p className="text-sm text-neutral-500 dark:text-neutral-400">画像を読み込み中…</p>}
      {loadError && (
        <div className="flex items-center gap-3 text-sm text-red-600" role="alert">
          <p>
            {loadError.label}: {loadError.message}
          </p>
          <button type="button" onClick={loadError.retry} className="underline">
            再試行
          </button>
        </div>
      )}

      {selectedId !== null && editing && detail && (
        <ImageEditDialog
          imageId={detail.id}
          width={detail.width}
          height={detail.height}
          onSaved={handleEditSaved}
          onCancel={() => setEditing(false)}
        />
      )}

      {selectedId !== null && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={closeDetail}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white dark:bg-neutral-900 p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-start justify-between gap-4">
              <h2 className="text-lg font-semibold">生成画像の詳細</h2>
              <div className="flex items-center gap-3">
                {detail && (
                  <button
                    type="button"
                    onClick={() => setEditing(true)}
                    className="text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-100"
                  >
                    編集
                  </button>
                )}
                {detail && (
                  <button
                    type="button"
                    onClick={handleCopySettings}
                    className="text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-100"
                  >
                    {settingsCopied ? "コピーしました" : "この画像の設定をコピー"}
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => handleDelete(selectedId)}
                  disabled={isDeleting}
                  className="text-red-600 hover:text-red-800 disabled:opacity-50"
                >
                  {isDeleting ? "削除中…" : "削除"}
                </button>
                <button type="button" onClick={closeDetail} className="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-300">
                  閉じる
                </button>
              </div>
            </div>

            <img
              src={`/image-gallery/${selectedId}/file`}
              alt="生成画像"
              className="mb-4 w-full rounded border border-neutral-200 dark:border-neutral-800"
            />

            {editSavedMessage && (
              <p role="status" className="mb-4 text-sm text-green-700 dark:text-green-400">
                {editSavedMessage}
              </p>
            )}
            {isPending && <p className="text-neutral-500 dark:text-neutral-400">読み込み中…</p>}
            {error && <p className="text-red-600">{error}</p>}
            {detail && (
              <>
                <div className="mb-4 space-y-2">
                  <p className="text-sm font-semibold">フォルダ</p>
                  <select
                    aria-label="所属フォルダ"
                    value={String(detail.folderId ?? "")}
                    onChange={(e) => handleFolderChange(detail.id, e.target.value)}
                    disabled={isSavingFolder}
                    className="w-full rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-sm"
                  >
                    <option value="">未分類</option>
                    {folderOptions.map(({ folder, depth }) => (
                      <option key={folder.id} value={folder.id}>
                        {"— ".repeat(depth)}
                        {folder.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="mb-4 space-y-2">
                  <p className="text-sm font-semibold">タグ</p>
                  <div className="flex flex-wrap items-center gap-2">
                    {detail.tags.length === 0 && (
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">タグはありません。</span>
                    )}
                    {detail.tags.map((tag) => (
                      <span
                        key={tag}
                        className="flex items-center gap-1 rounded-full bg-neutral-100 dark:bg-neutral-800 px-2.5 py-1 text-xs text-neutral-700 dark:text-neutral-300"
                      >
                        {tag}
                        <button
                          type="button"
                          onClick={() => handleRemoveTag(tag)}
                          disabled={isSavingTags}
                          aria-label={`タグ「${tag}」を削除`}
                          className="text-neutral-400 hover:text-red-600 disabled:opacity-50"
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </div>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={newTag}
                      onChange={(e) => setNewTag(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          handleAddTag();
                        }
                      }}
                      placeholder="タグを追加"
                      disabled={isSavingTags}
                      className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-sm"
                    />
                    <button
                      type="button"
                      onClick={handleAddTag}
                      disabled={isSavingTags || !newTag.trim()}
                      className="rounded bg-neutral-900 px-3 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
                    >
                      {isSavingTags ? "保存中…" : "追加"}
                    </button>
                  </div>
                </div>

                <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                  <dt className="col-span-2 font-semibold">prompt</dt>
                  <dd className="col-span-2 whitespace-pre-wrap text-neutral-600 dark:text-neutral-400">{detail.prompt}</dd>
                  <dt className="col-span-2 font-semibold">negative prompt</dt>
                  <dd className="col-span-2 whitespace-pre-wrap text-neutral-600 dark:text-neutral-400">{detail.negativePrompt || "-"}</dd>
                  <dt className="font-semibold">steps</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.steps}</dd>
                  <dt className="font-semibold">cfg scale</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.cfgScale}</dd>
                  <dt className="font-semibold">sampler</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.samplerName}</dd>
                  <dt className="font-semibold">scheduler</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.scheduler}</dd>
                  <dt className="font-semibold">seed</dt>
                  {/*
                    issue #1101: seedが無い画像は再現できないので、値の代わりにそう分かる表示にする。
                    ChatGPTの画像生成API(gpt-image-1)はseedを受け付けないため常にここへ来る。
                    #1101以前に生成した画像も、実際に使われたseedが残っていないので同じ扱いになる。
                  */}
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.seed != null ? detail.seed : "再現不可(この画像生成AIはseedに対応していません)"}
                  </dd>
                  <dt className="font-semibold">size</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.width}x{detail.height}
                  </dd>
                  <dt className="font-semibold">batch size</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.batchSize}</dd>
                  {/* issue #1101: 同じseed・同じbatch sizeで再実行したときの、この画像の位置。 */}
                  <dt className="font-semibold">batch index</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.batchIndex != null ? detail.batchIndex : "-"}
                  </dd>
                  <dt className="font-semibold">画像生成AI</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {PROVIDER_LABEL[detail.provider] ?? detail.provider}
                  </dd>
                  <dt className="font-semibold">checkpoint</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.checkpoint}</dd>
                  <dt className="font-semibold">LoRA</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.loraName ? `${detail.loraName} (weight: ${detail.loraWeight})` : "-"}
                  </dd>
                  {/*
                    issue #1601: 参照画像を使って(img2img)生成した画像は、参照元の画像IDとサムネイルを示す。
                    参照元が後から削除されていてもIDは残る(サムネイルだけ読み込めなくなる)。
                  */}
                  {detail.sourceImageId != null && (
                    <>
                      <dt className="font-semibold">参照元の画像</dt>
                      <dd className="space-y-1 text-neutral-600 dark:text-neutral-400">
                        <span className="block">ID {detail.sourceImageId}</span>
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={`/image-gallery/${detail.sourceImageId}/file`}
                          alt={`参照元の画像 ${detail.sourceImageId}`}
                          className="h-24 w-24 rounded bg-neutral-100 object-contain dark:bg-neutral-800"
                        />
                      </dd>
                    </>
                  )}
                  <dt className="font-semibold">作成日時</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {timezone
                      ? formatDateTime(detail.createdAt, timezone)
                      : mounted
                        ? formatDateTime(detail.createdAt)
                        : TIMEZONE_PENDING_PLACEHOLDER}
                  </dd>
                </dl>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
