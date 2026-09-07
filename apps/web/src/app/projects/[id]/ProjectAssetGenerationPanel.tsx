"use client";

import { useState } from "react";
import type {
  AiImageGenerationParams,
  AiImageResult,
  GeneratedImageSummary,
  ImageGenerationOptionsResponse,
  PlanChatMessage,
} from "@/lib/apiClient";
import {
  fetchGeneratedImagesAction,
  fetchImageGenerationOptionsAction,
  generateImagePromptAction,
  generateProjectImagesAction,
  uploadProjectAssetImageAction,
} from "./actions";

/**
 * プロジェクト管理画面でComfyUI画像を生成し(automatic1111相当のパラメータ)、
 * 選択した1枚をlocal/test/production全環境へアセットとしてアップロードするパネル。
 * フォーム項目はVSCode拡張のimageGenPanel.tsと揃えている。
 *
 * 1回の要求で生成する枚数は batch size × batch count(issue #1103)。どちらも上限は16で、
 * 掛け算の合計には上限が無い(#1102 の決定)。合計を止めない代わりに、利用者が枚数を
 * 自分で判断できるよう、合計枚数と所要時間の目安をフォームに示す。
 */
export function ProjectAssetGenerationPanel({ projectId }: { projectId: number }) {
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState<ImageGenerationOptionsResponse | null>(null);
  const [loadingOptions, setLoadingOptions] = useState(false);

  const [prompt, setPrompt] = useState("");
  const [negativePrompt, setNegativePrompt] = useState("");
  const [steps, setSteps] = useState(20);
  const [cfgScale, setCfgScale] = useState(7.0);
  const [seed, setSeed] = useState("");
  const [samplerName, setSamplerName] = useState("");
  const [scheduler, setScheduler] = useState("");
  const [width, setWidth] = useState(1920);
  const [height, setHeight] = useState(1080);
  const [batchSize, setBatchSize] = useState(4);
  // リピート回数。1回の要求のうちにサーバー側で繰り返され、リピートごとにseedが変わる(issue #1103)。
  const [batchCount, setBatchCount] = useState(1);
  const [checkpoint, setCheckpoint] = useState("");
  const [loraName, setLoraName] = useState("");
  const [loraWeight, setLoraWeight] = useState(1.0);

  const [generating, setGenerating] = useState(false);
  // 生成中に表示する「要求した」総枚数。生成中に入力を変えても要求時の枚数を出し続ける。
  const [requestedTotal, setRequestedTotal] = useState(0);
  const [images, setImages] = useState<AiImageResult[] | null>(null);
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

  async function handleGenerate() {
    if (!prompt.trim()) {
      setMessage({ type: "error", text: "promptを入力してください。" });
      return;
    }
    setGenerating(true);
    setRequestedTotal(batchSize * batchCount);
    setMessage(null);
    setImages(null);
    setSelectedId(null);
    const result = await generateProjectImagesAction(projectId, {
      prompt,
      negativePrompt: negativePrompt || undefined,
      steps,
      cfgScale,
      samplerName: samplerName || undefined,
      scheduler: scheduler || undefined,
      seed: seed.trim() ? Number(seed) : null,
      width,
      height,
      batchSize,
      batchCount,
      checkpoint: checkpoint || undefined,
      loraName: loraName || undefined,
      loraWeight: loraName ? loraWeight : undefined,
    });
    setGenerating(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    setImages(result.images ?? []);
    setMessage({ type: "success", text: "生成しました。アセットとして追加する画像を選択してください。" });
  }

  /**
   * 生成画像ギャラリーの「この画像の設定をコピー」でコピーされたJSONをクリップボードから読み取り、
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

  /** 生成画像ギャラリーに保存済みの画像を選択肢として読み込む(issue #436)。 */
  async function handleGalleryToggle() {
    setGalleryOpen((v) => !v);
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

  async function handleGalleryUpload() {
    if (gallerySelectedId == null) return;
    await uploadGeneratedImage(gallerySelectedId, setGalleryUploading);
  }

  if (!open) {
    return (
      <section className="rounded border p-4">
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
    <section className="space-y-4 rounded border p-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">アセット画像生成</h2>
        <button type="button" onClick={() => setOpen(false)} className="text-sm text-gray-500 hover:underline">
          閉じる
        </button>
      </div>

      <div className="space-y-3 rounded border bg-gray-50 p-3">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">生成画像ギャラリーから選択してアップロード</h3>
          <button type="button" onClick={handleGalleryToggle} className="text-xs text-gray-500 hover:underline">
            {galleryOpen ? "閉じる" : "開く"}
          </button>
        </div>
        {galleryOpen && (
          <>
            {galleryLoading && <p className="text-xs text-gray-500">読み込んでいます…</p>}
            {galleryImages && galleryImages.length === 0 && (
              <p className="text-xs text-gray-500">生成画像ギャラリーに画像がありません。</p>
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
                        alt={img.prompt}
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
        <p className="text-sm text-gray-500">パラメータ選択肢を読み込んでいます…</p>
      ) : (
        <div className="grid gap-3">
          <div className="space-y-2 rounded border bg-gray-50 p-3">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold">チャットでプロンプトを作成</h3>
              <button
                type="button"
                onClick={() => setChatOpen((v) => !v)}
                className="text-xs text-gray-500 hover:underline"
              >
                {chatOpen ? "閉じる" : "開く"}
              </button>
            </div>
            {chatOpen && (
              <>
                <label className="flex items-center gap-2 text-xs text-gray-600">
                  <span>AIプロバイダー</span>
                  <select
                    value={chatProvider}
                    onChange={(e) => setChatProvider(e.target.value)}
                    disabled={chatLoading}
                    className="rounded border p-1 text-xs disabled:bg-gray-100"
                  >
                    <option value="">(プロジェクト/グローバル既定を使用)</option>
                    <option value="OLLAMA">Ollama</option>
                    <option value="OPENAI">OpenAI (ChatGPT)</option>
                    <option value="CLAUDE">Claude (Anthropic)</option>
                  </select>
                </label>
                <div className="max-h-48 space-y-2 overflow-y-auto rounded bg-white p-2">
                  {chatHistory.length === 0 ? (
                    <p className="text-xs text-gray-500">
                      作りたい画像の内容をチャットで伝えてください。生成されたプロンプトが下のprompt欄に反映されます。
                    </p>
                  ) : (
                    chatHistory.map((msg, idx) => (
                      <div
                        key={idx}
                        className={`rounded px-2 py-1 text-xs ${
                          msg.role === "user" ? "bg-blue-100 text-blue-900" : "bg-gray-200 text-gray-900"
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
                    className="flex-1 rounded border p-2 text-xs disabled:bg-gray-100"
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
          <div>
            <label className="block text-sm font-medium">prompt</label>
            <textarea
              className="mt-1 w-full rounded border p-2 text-sm"
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
            <label className="block text-sm font-medium">negative prompt</label>
            <textarea
              className="mt-1 w-full rounded border p-2 text-sm"
              value={negativePrompt}
              onChange={(e) => setNegativePrompt(e.target.value)}
              placeholder={options?.defaultNegativePrompt ?? "low quality, blurry, watermark, text"}
            />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="block text-sm font-medium">steps</label>
              <input
                type="number"
                className="mt-1 w-full rounded border p-2 text-sm"
                value={steps}
                min={1}
                max={150}
                onChange={(e) => setSteps(Number(e.target.value))}
              />
            </div>
            <div>
              <label className="block text-sm font-medium">cfg scale</label>
              <input
                type="number"
                step={0.1}
                className="mt-1 w-full rounded border p-2 text-sm"
                value={cfgScale}
                onChange={(e) => setCfgScale(Number(e.target.value))}
              />
            </div>
            <div>
              <label className="block text-sm font-medium">seed(空欄でランダム)</label>
              <input
                type="text"
                className="mt-1 w-full rounded border p-2 text-sm"
                value={seed}
                onChange={(e) => setSeed(e.target.value)}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium">sampler</label>
              <select
                className="mt-1 w-full rounded border p-2 text-sm"
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
              <label className="block text-sm font-medium">scheduler</label>
              <select
                className="mt-1 w-full rounded border p-2 text-sm"
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
              <label className="block text-sm font-medium">width</label>
              <input
                type="number"
                step={8}
                className="mt-1 w-full rounded border p-2 text-sm"
                value={width}
                onChange={(e) => setWidth(Number(e.target.value))}
              />
            </div>
            <div>
              <label className="block text-sm font-medium">height</label>
              <input
                type="number"
                step={8}
                className="mt-1 w-full rounded border p-2 text-sm"
                value={height}
                onChange={(e) => setHeight(Number(e.target.value))}
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
                className="mt-1 w-full rounded border p-2 text-sm"
                value={batchSize}
                onChange={(e) => setBatchSize(Number(e.target.value))}
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
                className="mt-1 w-full rounded border p-2 text-sm"
                value={batchCount}
                onChange={(e) => setBatchCount(Number(e.target.value))}
              />
              <p id="asset-batch-count-help" className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                batch size枚の生成を繰り返す回数です。リピートのたびにseedが変わるので、同じ設定のまま違う絵柄の候補を増やせます。
              </p>
            </div>
          </div>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">
            この設定で合計{batchSize * batchCount}枚(batch size {batchSize} × batch count {batchCount})を生成します。
            合計枚数に上限はありませんが、枚数に比例して時間がかかり、最大の256枚では非常に長時間かかります。
          </p>
          <div>
            <label className="block text-sm font-medium">checkpoint</label>
            <select
              className="mt-1 w-full rounded border p-2 text-sm"
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
              <label className="block text-sm font-medium">LoRA</label>
              <select
                className="mt-1 w-full rounded border p-2 text-sm"
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
                <label className="block text-sm font-medium">LoRA weight</label>
                <input
                  type="number"
                  step={0.1}
                  min={0}
                  max={2}
                  className="mt-1 w-full rounded border p-2 text-sm"
                  value={loraWeight}
                  onChange={(e) => setLoraWeight(Number(e.target.value))}
                />
              </div>
            )}
          </div>

          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={handleGenerate}
              disabled={generating}
              className="w-fit rounded bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
            >
              {generating ? "生成しています…" : "生成"}
            </button>
            <button
              type="button"
              onClick={handleCreateFromClipboard}
              className="w-fit rounded border border-gray-400 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100"
            >
              クリップボードから作成
            </button>
          </div>
          {generating && (
            <p
              aria-live="polite"
              aria-atomic="true"
              className="text-sm text-neutral-600 dark:text-neutral-300"
            >
              合計{requestedTotal}枚を生成しています。枚数によっては非常に長い時間がかかります。完了するまでこのページを離れないでください。
            </p>
          )}
        </div>
      )}

      {images && images.length > 0 && (
        <div className="space-y-3">
          <p className="text-sm text-neutral-600 dark:text-neutral-300">
            生成された{images.length}枚から、アセットにする1枚を選んでください。
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
                {/*
                  data URIをそのままDOMに置くため、最大256枚ぶんのbase64(1920×1080なら
                  1枚1MBを超えうる)が同時に載る。loading="lazy"で画面外のサムネイルの
                  デコード・描画をブラウザに遅らせ、実コストを下げる。
                  残存リスク: 実ブラウザ・実サイズ256枚での描画コストは未検証。ChatGPT
                  スタブがnを10でクランプするため受入テストは生成開始前に止まり、目視
                  確認は #1097(e2eアカウントのKeycloak認証)でブロックされている。jest側
                  では1枚40KB相当×256枚でReactとDOMが壊れないことまでを確認している。
                */}
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={`data:${img.mimeType};base64,${img.dataBase64}`}
                  alt={img.fileName}
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
