"use client";

import { useEffect, useState } from "react";
import type {
  AiImageGenerationParams,
  GeneratedImageSummary,
  ImageGenerationOptionsResponse,
  PlanChatMessage,
} from "@/lib/apiClient";
import {
  fetchGeneratedImagesAction,
  fetchImageGenerationOptionsAction,
  fetchImageJobResultAction,
  generateImagePromptAction,
  requestProjectImageJobAction,
  uploadGeneratedImageAction,
  uploadProjectAssetImageAction,
} from "./actions";

/** 画像アップロードで選べる形式(issue #1599)。サーバーも中身で同じ判定をする。 */
const UPLOAD_ACCEPTED_TYPES = ["image/jpeg", "image/png"];
/** 画像アップロードのファイルサイズ上限(issue #1599。サーバーのmultipart上限と同じ20MB)。 */
const UPLOAD_MAX_BYTES = 20 * 1024 * 1024;

/**
 * パネル外枠のカード。同じ「AI」タブに並ぶ兄弟パネル ProjectAiModelsPanel.tsx と同じ
 * クラス列にして、角丸半径・ボーダー色・背景を揃える(issue #1107)。Tailwind v4 では
 * 色指定のない `border` の既定色が currentColor になり本文色でボーダーが描かれるため、
 * 色は必ず明示する。
 */
const PANEL_CLASS =
  "rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4";

/**
 * ネストする2つのサブフォーム(ギャラリー / チャット)のコンテナ。同じ階層・同じ役割なので
 * 互いに同一のクラス列にする(issue #1107)。定数に切り出してあるのは、両者が一致している
 * ことを ProjectAssetGenerationPanel.test.tsx が className の比較で検査するため。
 *
 * 背景に dark: 対が無いと、文字色を持たない中の <h3> が body の dark:text-neutral-50 を
 * 継承してほぼ白の背景に載り、コントラスト比が約1.0:1 になって読めなくなる。
 */
const SUBSECTION_CLASS =
  "space-y-3 rounded border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3";

/** 数値入力を空にしたとき、生成要求に送る値(各useStateの初期値と同じ)。 */
const DEFAULTS = {
  steps: 20,
  cfgScale: 7.0,
  width: 1920,
  height: 1080,
  batchSize: 4,
  batchCount: 1,
  loraWeight: 1.0,
  // 参照画像(img2img)の変化の強さ。サーバーの既定と同じ(issue #1601)。
  denoise: 0.6,
};

/** 空文字列は空欄のまま保持する。Number("") は0になり、入力欄が勝手に「0」へ書き換わるため(#1115)。 */
function toNumberOrEmpty(value: string): number | "" {
  return value === "" ? "" : Number(value);
}

/**
 * プロジェクト管理画面でComfyUI画像を生成し(automatic1111相当のパラメータ)、
 * 選択した1枚をlocal/test/production全環境へアセットとしてアップロードするパネル。
 * フォーム項目はVSCode拡張のimageGenPanel.tsと揃えている。
 *
 * 1回の要求で生成する枚数は batch size × batch count(issue #1103)。どちらも上限は16で、
 * 掛け算の合計には上限が無い(#1102 の決定)。合計を止めない代わりに、利用者が枚数を
 * 自分で判断できるよう、合計枚数と所要時間の目安をフォームに示す。
 *
 * 生成は非同期のジョブとして要求する(issue #1408)。受理された時点でパネルは開放され、ジョブは
 * 情報表示レールの処理キューに現れる。完了後は、処理キューの「結果を見る」が
 * `?imageJob=<ジョブID>` 付きでこのパネルへ導き(`imageJobId`)、そのジョブが生成した画像だけを
 * 表示する。画像の選択とアセットとしてのアップロードは従来どおり。ページを離れても、生成結果の
 * 表示先はジョブ(サーバー側)に残る。
 */
export function ProjectAssetGenerationPanel({
  projectId,
  imageJobId,
}: {
  projectId: number;
  imageJobId?: number;
}) {
  const [open, setOpen] = useState(imageJobId !== undefined);
  const [options, setOptions] = useState<ImageGenerationOptionsResponse | null>(null);
  const [loadingOptions, setLoadingOptions] = useState(false);

  const [prompt, setPrompt] = useState("");
  const [negativePrompt, setNegativePrompt] = useState("");
  const [steps, setSteps] = useState<number | "">(20);
  const [cfgScale, setCfgScale] = useState<number | "">(7.0);
  const [seed, setSeed] = useState("");
  const [samplerName, setSamplerName] = useState("");
  const [scheduler, setScheduler] = useState("");
  const [width, setWidth] = useState<number | "">(1920);
  const [height, setHeight] = useState<number | "">(1080);
  const [batchSize, setBatchSize] = useState<number | "">(4);
  // リピート回数。1回の要求のうちにサーバー側で繰り返され、リピートごとにseedが変わる(issue #1103)。
  const [batchCount, setBatchCount] = useState<number | "">(1);
  const [checkpoint, setCheckpoint] = useState("");
  const [loraName, setLoraName] = useState("");
  const [loraWeight, setLoraWeight] = useState<number | "">(1.0);

  // 表示中のジョブが生成した画像(ID のみ。画像本体は /image-gallery/{id}/file から読む)。
  const [images, setImages] = useState<{ id: number }[] | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  const [chatOpen, setChatOpen] = useState(false);
  const [chatHistory, setChatHistory] = useState<PlanChatMessage[]>([]);
  const [chatInput, setChatInput] = useState("");
  const [chatLoading, setChatLoading] = useState(false);
  const [chatError, setChatError] = useState<string | undefined>(undefined);
  // このチャットで使うAIプロバイダー(空文字はプロジェクト/グローバル既定を使用、issue #530)。
  const [chatProvider, setChatProvider] = useState("");

  const [galleryOpen, setGalleryOpen] = useState(false);
  const [galleryImages, setGalleryImages] = useState<GeneratedImageSummary[] | null>(null);
  const [galleryLoading, setGalleryLoading] = useState(false);
  const [gallerySelectedId, setGallerySelectedId] = useState<number | null>(null);
  const [galleryUploading, setGalleryUploading] = useState(false);

  // img2imgの参照画像(issue #1601)。選択肢はギャラリーの一覧(gallery*)を共用する。
  const [referenceOpen, setReferenceOpen] = useState(false);
  const [referenceId, setReferenceId] = useState<number | null>(null);
  const [denoise, setDenoise] = useState<number | "">(DEFAULTS.denoise);

  // 手元の画像のアップロード(issue #1599)。
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [imageUploading, setImageUploading] = useState(false);
  // 登録後にファイル入力の表示も空へ戻す(同じファイルをもう一度選べるようにする)ための鍵。
  const [uploadInputKey, setUploadInputKey] = useState(0);

  async function handleOpen() {
    setOpen(true);
    if (options) return;
    setLoadingOptions(true);
    try {
      const opts = await fetchImageGenerationOptionsAction(projectId);
      setOptions(opts);
      setSamplerName(opts.samplers[0] ?? "euler");
      setScheduler(opts.schedulers[0] ?? "normal");
      setCheckpoint(opts.selectedCheckpoint ?? "");
      // プロジェクトのデフォルト生成サイズを初期値として反映する(issue #292)。
      if (opts.defaultWidth) setWidth(opts.defaultWidth);
      if (opts.defaultHeight) setHeight(opts.defaultHeight);
    } catch (err) {
      setMessage({ type: "error", text: err instanceof Error ? err.message : String(err) });
    } finally {
      setLoadingOptions(false);
    }
  }

  // 処理キューの「結果を見る」から来たとき、パネルを開き、そのジョブが生成した画像を取得する。
  useEffect(() => {
    if (imageJobId === undefined) return;
    let cancelled = false;
    // 選択肢の取得(setLoadingOptions)を伴う。パネルを手で開いたときと同じ経路を使う。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void handleOpen();
    fetchImageJobResultAction(imageJobId).then((result) => {
      if (cancelled) return;
      if (result.error) {
        setMessage({ type: "error", text: result.error });
        return;
      }
      setImages(result.images ?? []);
    });
    return () => {
      cancelled = true;
    };
    // handleOpen は毎回作り直される関数。ジョブが変わったときだけ取得し直す。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imageJobId]);

  // 参照画像にできるのは、このプロジェクトのギャラリー画像だけ(別プロジェクトの画像はサーバーが拒否する)。
  const referenceCandidates = galleryImages?.filter((img) => img.projectId === projectId) ?? null;

  const effectiveBatchSize = batchSize === "" ? DEFAULTS.batchSize : batchSize;
  const effectiveBatchCount = batchCount === "" ? DEFAULTS.batchCount : batchCount;

  async function handleGenerate() {
    if (!prompt.trim()) {
      setMessage({ type: "error", text: "promptを入力してください。" });
      return;
    }
    // 参照画像を選んでいるときだけ、参照画像IDと変化の強さを送る(選ばなければ従来のtxt2img)。
    let referenceParams: { referenceImageId?: number; denoise?: number } = {};
    if (referenceId != null) {
      const effectiveDenoise = denoise === "" ? DEFAULTS.denoise : denoise;
      if (effectiveDenoise < 0 || effectiveDenoise > 1) {
        setMessage({ type: "error", text: "denoiseは0〜1の範囲で指定してください。" });
        return;
      }
      referenceParams = { referenceImageId: referenceId, denoise: effectiveDenoise };
    }
    setMessage(null);
    const result = await requestProjectImageJobAction(projectId, {
      prompt,
      negativePrompt: negativePrompt || undefined,
      steps: steps === "" ? DEFAULTS.steps : steps,
      cfgScale: cfgScale === "" ? DEFAULTS.cfgScale : cfgScale,
      samplerName: samplerName || undefined,
      scheduler: scheduler || undefined,
      seed: seed.trim() ? Number(seed) : null,
      width: width === "" ? (options?.defaultWidth || DEFAULTS.width) : width,
      height: height === "" ? (options?.defaultHeight || DEFAULTS.height) : height,
      batchSize: effectiveBatchSize,
      batchCount: effectiveBatchCount,
      checkpoint: checkpoint || undefined,
      loraName: loraName || undefined,
      loraWeight: loraName ? (loraWeight === "" ? DEFAULTS.loraWeight : loraWeight) : undefined,
      ...referenceParams,
    });
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    if (result.status === "failed") {
      // 実行枠と待ち行列が満杯のとき、ジョブは作られた上で failed として返る。
      setMessage({ type: "error", text: "画像生成の待ち行列が満杯です。しばらくしてからもう一度要求してください。" });
      return;
    }
    setMessage({
      type: "success",
      text: "生成を要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から画像を確認できます。",
    });
  }

  /**
   * 画像ギャラリーの「この画像の設定をコピー」でコピーされたJSONをクリップボードから読み取り、
   * フォームに反映する(issue #437)。
   */
  async function handleCreateFromClipboard() {
    setMessage(null);
    let text: string;
    try {
      text = await navigator.clipboard.readText();
    } catch (err) {
      setMessage({ type: "error", text: err instanceof Error ? err.message : String(err) });
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      setMessage({ type: "error", text: "クリップボードの内容が正しいJSON形式ではありません。" });
      return;
    }
    if (typeof parsed !== "object" || parsed === null || typeof (parsed as { prompt?: unknown }).prompt !== "string") {
      setMessage({ type: "error", text: "クリップボードの内容から生成設定を読み取れませんでした。" });
      return;
    }

    const settings = parsed as Partial<AiImageGenerationParams>;
    setPrompt(settings.prompt ?? "");
    setNegativePrompt(settings.negativePrompt ?? "");
    if (typeof settings.steps === "number") setSteps(settings.steps);
    if (typeof settings.cfgScale === "number") setCfgScale(settings.cfgScale);
    setSamplerName(settings.samplerName ?? "");
    setScheduler(settings.scheduler ?? "");
    setSeed(settings.seed != null ? String(settings.seed) : "");
    if (typeof settings.width === "number") setWidth(settings.width);
    if (typeof settings.height === "number") setHeight(settings.height);
    if (typeof settings.batchSize === "number") setBatchSize(settings.batchSize);
    // ギャラリーのコピー用JSONはbatchCountを持たない(#1102の方針)。持たない設定を読み込んだら、
    // 前の入力を引きずらずリピート1回=コピー元の1枚を再現する形に戻す。
    setBatchCount(typeof settings.batchCount === "number" ? settings.batchCount : 1);
    setCheckpoint(settings.checkpoint ?? "");
    setLoraName(settings.loraName ?? "");
    if (typeof settings.loraWeight === "number") setLoraWeight(settings.loraWeight);
    setMessage({ type: "success", text: "クリップボードの設定をフォームに反映しました。" });
  }

  async function handleChatSend() {
    const chatMessage = chatInput.trim();
    if (!chatMessage || chatLoading) return;
    setChatInput("");
    setChatLoading(true);
    setChatError(undefined);
    const result = await generateImagePromptAction(projectId, {
      history: chatHistory,
      message: chatMessage,
      provider: chatProvider || undefined,
    });
    setChatLoading(false);
    if (result.error) {
      setChatError(result.error);
      return;
    }
    const generatedPrompt = result.prompt ?? "";
    setChatHistory((prev) => [...prev, { role: "user", content: chatMessage }, { role: "assistant", content: generatedPrompt }]);
    setPrompt(generatedPrompt);
  }

  async function uploadGeneratedImage(generatedImageId: number, setUploadingFlag: (v: boolean) => void) {
    setUploadingFlag(true);
    setMessage(null);
    const result = await uploadProjectAssetImageAction(projectId, generatedImageId);
    setUploadingFlag(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    const logs = result.logs ?? [];
    const failed = logs.filter((l) => l.status !== "SUCCESS");
    if (failed.length === 0) {
      setMessage({ type: "success", text: `全${logs.length}環境へアップロードしました。` });
    } else {
      setMessage({
        type: "error",
        text: `${failed.map((l) => l.environment).join(", ")}環境でアップロードに失敗しました。`,
      });
    }
  }

  async function handleUpload() {
    if (selectedId == null) return;
    await uploadGeneratedImage(selectedId, setUploading);
  }

  /** ファイルを選んだ時点で、形式とサイズを検査する。送る前に弾くので、無駄な要求がサーバーの枠を消費しない。 */
  function handleUploadFileChange(file: File | null) {
    setMessage(null);
    setUploadFile(null);
    setUploadError(null);
    if (!file) return;
    if (!UPLOAD_ACCEPTED_TYPES.includes(file.type)) {
      setUploadError("対応していない画像形式です。JPEGまたはPNGを選択してください。");
      return;
    }
    if (file.size > UPLOAD_MAX_BYTES) {
      setUploadError("ファイルサイズが上限(20MB)を超えています。");
      return;
    }
    setUploadFile(file);
  }

  async function handleImageUpload() {
    if (!uploadFile) return;
    setImageUploading(true);
    setMessage(null);
    const formData = new FormData();
    formData.append("file", uploadFile);
    const result = await uploadGeneratedImageAction(projectId, formData);
    setImageUploading(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    setMessage({
      type: "success",
      text: `画像を元の解像度のまま、画像ギャラリーに登録しました(画像ID: ${result.imageId})。`,
    });
    setUploadFile(null);
    setUploadInputKey((key) => key + 1);
    // 一覧を開いて読み込んであるときは、登録した画像がすぐ並ぶよう取得し直す。
    if (galleryImages) {
      try {
        setGalleryImages(await fetchGeneratedImagesAction());
      } catch (err) {
        setMessage({ type: "error", text: err instanceof Error ? err.message : String(err) });
      }
    }
  }

  /** 画像ギャラリーに保存済みの画像を選択肢として読み込む(issue #436)。読み込み済みなら何もしない。 */
  async function loadGalleryImages() {
    if (galleryImages || galleryLoading) return;
    setGalleryLoading(true);
    try {
      const imgs = await fetchGeneratedImagesAction();
      setGalleryImages(imgs);
    } catch (err) {
      setMessage({ type: "error", text: err instanceof Error ? err.message : String(err) });
    } finally {
      setGalleryLoading(false);
    }
  }

  async function handleGalleryToggle() {
    setGalleryOpen((v) => !v);
    await loadGalleryImages();
  }

  /** 参照画像の選択肢を開閉する(issue #1601)。開くときにギャラリーの一覧を読み込む。 */
  async function handleReferenceToggle() {
    setReferenceOpen((v) => !v);
    await loadGalleryImages();
  }

  async function handleGalleryUpload() {
    if (gallerySelectedId == null) return;
    await uploadGeneratedImage(gallerySelectedId, setGalleryUploading);
  }

  if (!open) {
    return (
      <section className={PANEL_CLASS}>
        <button
          type="button"
          onClick={handleOpen}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          アセット画像生成
        </button>
      </section>
    );
  }

  return (
    <section className={`space-y-4 ${PANEL_CLASS}`}>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">アセット画像生成</h2>
        <button type="button" onClick={() => setOpen(false)} className="text-sm text-neutral-500 dark:text-neutral-400 hover:underline">
          閉じる
        </button>
      </div>

      <div className={SUBSECTION_CLASS}>
        <h3 className="text-sm font-semibold">画像をアップロードしてギャラリーへ登録</h3>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">
          JPEG/PNG(20MBまで)を1枚選べます。切り抜き・拡大・縮小はせず、元の解像度のまま登録します。
          EXIF/GPSなどのメタ情報は取り除かれます。登録した画像は、生成画像と同じギャラリーに並びます。
        </p>
        <input
          key={uploadInputKey}
          type="file"
          accept={UPLOAD_ACCEPTED_TYPES.join(",")}
          aria-label="アップロードする画像ファイル"
          onChange={(e) => handleUploadFileChange(e.target.files?.[0] ?? null)}
          className="block text-xs"
        />
        {uploadError && (
          <p role="alert" className="text-xs text-red-600">
            {uploadError}
          </p>
        )}
        <button
          type="button"
          onClick={handleImageUpload}
          disabled={!uploadFile || imageUploading}
          className="rounded bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
        >
          {imageUploading ? "登録しています…" : "ギャラリーへ登録"}
        </button>
      </div>

      <div className={SUBSECTION_CLASS}>
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">画像ギャラリーから選択してアップロード</h3>
          <button type="button" onClick={handleGalleryToggle} className="text-xs text-neutral-500 dark:text-neutral-400 hover:underline">
            {galleryOpen ? "閉じる" : "開く"}
          </button>
        </div>
        {galleryOpen && (
          <>
            {galleryLoading && <p className="text-xs text-neutral-500 dark:text-neutral-400">読み込んでいます…</p>}
            {galleryImages && galleryImages.length === 0 && (
              <p className="text-xs text-neutral-500 dark:text-neutral-400">画像ギャラリーに画像がありません。</p>
            )}
            {galleryImages && galleryImages.length > 0 && (
              <>
                <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                  {galleryImages.map((img) => (
                    <button
                      type="button"
                      key={img.id}
                      onClick={() => setGallerySelectedId(img.id)}
                      className={`rounded border-2 p-1 ${
                        gallerySelectedId === img.id ? "border-blue-600" : "border-transparent"
                      }`}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={`/image-gallery/${img.id}/file`}
                        alt={img.prompt ?? "アップロード画像"}
                        className="aspect-square w-full rounded bg-neutral-100 object-contain dark:bg-neutral-800"
                      />
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  onClick={handleGalleryUpload}
                  disabled={gallerySelectedId == null || galleryUploading}
                  className="rounded bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
                >
                  {galleryUploading ? "アップロードしています…" : "選択した画像をアセットとして追加(全環境へアップロード)"}
                </button>
              </>
            )}
          </>
        )}
      </div>

      {loadingOptions ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">パラメータ選択肢を読み込んでいます…</p>
      ) : (
        <div className="grid gap-3">
          <div className={SUBSECTION_CLASS}>
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">チャットでプロンプトを作成</h3>
              <button
                type="button"
                onClick={() => setChatOpen((v) => !v)}
                className="text-xs text-neutral-500 dark:text-neutral-400 hover:underline"
              >
                {chatOpen ? "閉じる" : "開く"}
              </button>
            </div>
            {chatOpen && (
              <>
                <label
                  htmlFor="asset-chat-provider"
                  className="flex items-center gap-2 text-xs text-neutral-600 dark:text-neutral-400"
                >
                  <span>AIプロバイダー</span>
                  <select
                    id="asset-chat-provider"
                    value={chatProvider}
                    onChange={(e) => setChatProvider(e.target.value)}
                    disabled={chatLoading}
                    className="rounded border border-neutral-300 dark:border-neutral-700 p-1 text-xs disabled:bg-neutral-100 dark:disabled:bg-neutral-800"
                  >
                    <option value="">(プロジェクト/グローバル既定を使用)</option>
                    <option value="OLLAMA">Ollama</option>
                    <option value="OPENAI">OpenAI (ChatGPT)</option>
                    <option value="CLAUDE">Claude (Anthropic)</option>
                  </select>
                </label>
                <div className="max-h-48 space-y-2 overflow-y-auto rounded bg-neutral-50 dark:bg-neutral-800 p-2">
                  {chatHistory.length === 0 ? (
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">
                      作りたい画像の内容をチャットで伝えてください。生成されたプロンプトが下のprompt欄に反映されます。
                    </p>
                  ) : (
                    chatHistory.map((msg, idx) => (
                      <div
                        key={idx}
                        className={`rounded px-2 py-1 text-xs ${
                          msg.role === "user"
                            ? "bg-blue-100 text-blue-900"
                            : "bg-neutral-200 dark:bg-neutral-700 text-neutral-900 dark:text-neutral-50"
                        }`}
                      >
                        <strong>{msg.role === "user" ? "あなた" : "生成プロンプト"}:</strong> {msg.content}
                      </div>
                    ))
                  )}
                </div>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={chatInput}
                    onChange={(e) => setChatInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !chatLoading) {
                        handleChatSend();
                      }
                    }}
                    placeholder="例: 夕焼けの海辺を歩く猫"
                    disabled={chatLoading}
                    className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 p-2 text-xs disabled:bg-neutral-100 dark:disabled:bg-neutral-800"
                  />
                  <button
                    type="button"
                    onClick={handleChatSend}
                    disabled={chatLoading || !chatInput.trim()}
                    className="rounded bg-neutral-900 px-3 py-2 text-xs text-white disabled:opacity-60"
                  >
                    {chatLoading ? "生成中…" : "プロンプト生成"}
                  </button>
                </div>
                {chatError && <p className="text-xs text-red-600">{chatError}</p>}
              </>
            )}
          </div>
          <div data-testid="reference-image-section" className={SUBSECTION_CLASS}>
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">参照画像(img2img)</h3>
              <button
                type="button"
                onClick={handleReferenceToggle}
                className="text-xs text-neutral-500 dark:text-neutral-400 hover:underline"
              >
                {referenceOpen ? "閉じる" : "参照画像を選ぶ"}
              </button>
            </div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">
              このプロジェクトのギャラリーの画像を1枚選ぶと、その構図・雰囲気を残して生成します(ComfyUIのみ)。
            </p>
            {referenceId != null && (
              <div className="space-y-2">
                <div className="flex items-center gap-3">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={`/image-gallery/${referenceId}/file`}
                    alt={`参照画像 ${referenceId}`}
                    className="h-16 w-16 rounded bg-neutral-100 object-contain dark:bg-neutral-800"
                  />
                  <span className="text-xs">選択中の参照画像: ID {referenceId}</span>
                  <button
                    type="button"
                    onClick={() => setReferenceId(null)}
                    className="text-xs text-red-600 hover:underline"
                  >
                    参照画像を解除
                  </button>
                </div>
                <div>
                  <label htmlFor="asset-denoise" className="block text-sm font-medium">
                    denoise(変化の強さ 0〜1、大きいほど参照画像から離れる)
                  </label>
                  <input
                    id="asset-denoise"
                    type="number"
                    step={0.05}
                    min={0}
                    max={1}
                    className="mt-1 w-32 rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                    value={denoise}
                    onChange={(e) => setDenoise(toNumberOrEmpty(e.target.value))}
                  />
                </div>
              </div>
            )}
            {referenceOpen && (
              <>
                {galleryLoading && <p className="text-xs text-neutral-500 dark:text-neutral-400">読み込んでいます…</p>}
                {referenceCandidates && referenceCandidates.length === 0 && (
                  <p className="text-xs text-neutral-500 dark:text-neutral-400">
                    このプロジェクトのギャラリーに参照できる画像がありません。
                  </p>
                )}
                {referenceCandidates && referenceCandidates.length > 0 && (
                  <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                    {referenceCandidates.map((img) => (
                      <button
                        type="button"
                        key={img.id}
                        onClick={() => setReferenceId(img.id)}
                        className={`rounded border-2 p-1 ${
                          referenceId === img.id ? "border-blue-600" : "border-transparent"
                        }`}
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={`/image-gallery/${img.id}/file`}
                          alt={img.prompt ?? "アップロード画像"}
                          className="aspect-square w-full rounded bg-neutral-100 object-contain dark:bg-neutral-800"
                        />
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
          <div>
            <label htmlFor="asset-prompt" className="block text-sm font-medium">prompt</label>
            <textarea
              id="asset-prompt"
              className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="生成したい画像の説明"
            />
            {options?.defaultQualityPrompt && (
              <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                生成時にpromptへ自動で追加されます: {options.defaultQualityPrompt}
              </p>
            )}
          </div>
          <div>
            <label htmlFor="asset-negative-prompt" className="block text-sm font-medium">negative prompt</label>
            <textarea
              id="asset-negative-prompt"
              className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
              value={negativePrompt}
              onChange={(e) => setNegativePrompt(e.target.value)}
              placeholder={options?.defaultNegativePrompt ?? "low quality, blurry, watermark, text"}
            />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label htmlFor="asset-steps" className="block text-sm font-medium">steps</label>
              <input
                id="asset-steps"
                type="number"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={steps}
                min={1}
                max={150}
                onChange={(e) => setSteps(toNumberOrEmpty(e.target.value))}
              />
            </div>
            <div>
              <label htmlFor="asset-cfg-scale" className="block text-sm font-medium">cfg scale</label>
              <input
                id="asset-cfg-scale"
                type="number"
                step={0.1}
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={cfgScale}
                onChange={(e) => setCfgScale(toNumberOrEmpty(e.target.value))}
              />
            </div>
            <div>
              <label htmlFor="asset-seed" className="block text-sm font-medium">seed(空欄でランダム)</label>
              <input
                id="asset-seed"
                type="text"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={seed}
                onChange={(e) => setSeed(e.target.value)}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="asset-sampler" className="block text-sm font-medium">sampler</label>
              <select
                id="asset-sampler"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={samplerName}
                onChange={(e) => setSamplerName(e.target.value)}
              >
                {(options?.samplers ?? []).map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="asset-scheduler" className="block text-sm font-medium">scheduler</label>
              <select
                id="asset-scheduler"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={scheduler}
                onChange={(e) => setScheduler(e.target.value)}
              >
                {(options?.schedulers ?? []).map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div>
              <label htmlFor="asset-width" className="block text-sm font-medium">width</label>
              <input
                id="asset-width"
                type="number"
                step={8}
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={width}
                onChange={(e) => setWidth(toNumberOrEmpty(e.target.value))}
              />
            </div>
            <div>
              <label htmlFor="asset-height" className="block text-sm font-medium">height</label>
              <input
                id="asset-height"
                type="number"
                step={8}
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={height}
                onChange={(e) => setHeight(toNumberOrEmpty(e.target.value))}
              />
            </div>
            <div>
              <label htmlFor="asset-batch-size" className="block text-sm font-medium">
                batch size(最大16)
              </label>
              <input
                id="asset-batch-size"
                type="number"
                min={1}
                max={16}
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={batchSize}
                onChange={(e) => setBatchSize(toNumberOrEmpty(e.target.value))}
              />
            </div>
            <div>
              <label htmlFor="asset-batch-count" className="block text-sm font-medium">
                batch count(最大16)
              </label>
              <input
                id="asset-batch-count"
                type="number"
                min={1}
                max={16}
                aria-describedby="asset-batch-count-help"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={batchCount}
                onChange={(e) => setBatchCount(toNumberOrEmpty(e.target.value))}
              />
              <p id="asset-batch-count-help" className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                batch size枚の生成を繰り返す回数です。リピートのたびにseedが変わるので、同じ設定のまま違う絵柄の候補を増やせます。
              </p>
            </div>
          </div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">
            この設定で合計{effectiveBatchSize * effectiveBatchCount}枚(batch size {effectiveBatchSize} × batch count {effectiveBatchCount})を生成します。
            合計枚数に上限はありませんが、枚数に比例して時間がかかり、最大の256枚では非常に長時間かかります。
          </p>
          <div>
            <label htmlFor="asset-checkpoint" className="block text-sm font-medium">checkpoint</label>
            <select
              id="asset-checkpoint"
              className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
              value={checkpoint}
              onChange={(e) => setCheckpoint(e.target.value)}
            >
              {(options?.checkpoints ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="asset-lora" className="block text-sm font-medium">LoRA</label>
              <select
                id="asset-lora"
                className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                value={loraName}
                onChange={(e) => setLoraName(e.target.value)}
              >
                <option value="">なし</option>
                {(options?.loras ?? []).map((l) => (
                  <option key={l} value={l}>
                    {l}
                  </option>
                ))}
              </select>
            </div>
            {loraName && (
              <div>
                <label htmlFor="asset-lora-weight" className="block text-sm font-medium">LoRA weight</label>
                <input
                  id="asset-lora-weight"
                  type="number"
                  step={0.1}
                  min={0}
                  max={2}
                  className="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 p-2 text-sm"
                  value={loraWeight}
                  onChange={(e) => setLoraWeight(toNumberOrEmpty(e.target.value))}
                />
              </div>
            )}
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={handleGenerate}
              className="w-fit rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
            >
              生成
            </button>
            <button
              type="button"
              onClick={handleCreateFromClipboard}
              className="w-fit rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm font-medium text-neutral-700 dark:text-neutral-200 hover:bg-neutral-100 dark:hover:bg-neutral-800"
            >
              クリップボードから作成
            </button>
          </div>
        </div>
      )}

      {images && images.length === 0 && (
        <p className="text-sm text-neutral-600 dark:text-neutral-300">このジョブが生成した画像はありません。</p>
      )}

      {images && images.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm text-neutral-600 dark:text-neutral-300">
            ジョブ #{imageJobId} が生成した{images.length}枚から、アセットにする1枚を選んでください。
          </p>
          {/*
            最大256枚(batch size 16 × batch count 16)が並びうるため、高さを固定して
            スクロールさせる。そうしないと下のアップロードボタンが画面外へ押し出される。
          */}
          <div
            data-testid="generated-image-grid"
            className="grid max-h-[32rem] grid-cols-2 gap-3 overflow-y-auto sm:grid-cols-4 lg:grid-cols-6"
          >
            {images.map((img) => (
              <button
                type="button"
                key={img.id}
                aria-pressed={selectedId === img.id}
                onClick={() => setSelectedId(img.id)}
                className={`rounded border-2 p-1 ${selectedId === img.id ? "border-blue-600" : "border-transparent"}`}
              >
                {/* 最大256枚が並びうるので、画面外のサムネイルはブラウザに遅延読み込みさせる。 */}
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={`/image-gallery/${img.id}/file`}
                  alt={`生成画像 ${img.id}`}
                  loading="lazy"
                  className="aspect-square w-full rounded bg-neutral-100 object-contain dark:bg-neutral-800"
                />
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={handleUpload}
            disabled={selectedId == null || uploading}
            className="rounded bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
          >
            {uploading ? "アップロードしています…" : "アセットとして追加(全環境へアップロード)"}
          </button>
        </div>
      )}

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}
    </section>
  );
}
