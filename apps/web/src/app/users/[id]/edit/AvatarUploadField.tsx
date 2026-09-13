"use client";

import { useRef, useState } from "react";
import { uploadAvatarAction, UploadAvatarState } from "./actions";

/** issue #1241 要件4: 受理する形式(GIF・SVGを含むそれ以外は拒否)。 */
const ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"];
/** issue #1241 要件5: 上限サイズ。 */
const MAX_SIZE_BYTES = 20 * 1024 * 1024;

interface CropRect {
  x: number;
  y: number;
  size: number;
}

/**
 * プロフィール編集画面のアバターアップロード・切り抜きUI(issue #1241)。
 *
 * ファイル選択後、選んだ画像の「中央の最大正方形」を初期選択した状態でプレビューし、
 * そのままクライアント側(Canvas)で切り抜いてアップロードする。既存の「アバターURL」
 * テキスト入力欄({@code UserProfileForm.tsx}側)はこのコンポーネントの外にあり、
 * このコンポーネントは触れない(要件1: 両立)。
 *
 * <p>切り抜き確定(要件2)は`useActionState`の暗黙のdispatchではなく、サーバーアクション
 * ({@link uploadAvatarAction})をイベントハンドラから直接awaitして呼ぶ。結果を
 * レンダー中やuseEffectでstateへ反映する経路(前者はReact 19の purity/refs lintルールに
 * 抵触し、後者は`react-hooks/set-state-in-effect`に抵触する)を避けるため。
 */
export function AvatarUploadField({
  userId,
  initialAvatarUrl,
}: {
  userId: number;
  initialAvatarUrl: string | null;
}) {
  const [pending, setPending] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [naturalSize, setNaturalSize] = useState<{ width: number; height: number } | null>(null);
  const [crop, setCrop] = useState<CropRect | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(initialAvatarUrl);

  const imgRef = useRef<HTMLImageElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  function resetFileInput() {
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    setLocalError(null);
    const file = e.target.files?.[0];
    if (!file) {
      return;
    }
    if (!ALLOWED_TYPES.includes(file.type)) {
      setLocalError("対応していない画像形式です(jpeg・png・webpのみアップロードできます)。");
      resetFileInput();
      return;
    }
    if (file.size > MAX_SIZE_BYTES) {
      setLocalError("ファイルサイズが上限(20MB)を超えています。");
      resetFileInput();
      return;
    }
    setImageUrl(URL.createObjectURL(file));
    setCrop(null);
    setNaturalSize(null);
  }

  function handleImageLoad() {
    const img = imgRef.current;
    if (!img) {
      return;
    }
    const width = img.naturalWidth;
    const height = img.naturalHeight;
    const size = Math.min(width, height);
    const x = Math.floor((width - size) / 2);
    const y = Math.floor((height - size) / 2);
    setNaturalSize({ width, height });
    setCrop({ x, y, size });
  }

  async function handleConfirm() {
    if (!crop || !imgRef.current) {
      return;
    }
    setLocalError(null);
    setPending(true);
    try {
      const canvas = document.createElement("canvas");
      canvas.width = crop.size;
      canvas.height = crop.size;
      const ctx = canvas.getContext("2d");
      if (!ctx) {
        setLocalError("この端末では画像の切り抜きに対応していません。");
        return;
      }
      ctx.drawImage(imgRef.current, crop.x, crop.y, crop.size, crop.size, 0, 0, crop.size, crop.size);
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/png"));
      if (!blob) {
        setLocalError("画像の生成に失敗しました。");
        return;
      }
      const file = new File([blob], "avatar.png", { type: "image/png" });
      const formData = new FormData();
      formData.append("file", file);

      const result: UploadAvatarState = await uploadAvatarAction(userId, {}, formData);
      if (result.error) {
        setLocalError(result.error);
        return;
      }

      setPreviewUrl(`/api/users/${userId}/avatar?v=${Date.now()}`);
      setImageUrl(null);
      setCrop(null);
      setNaturalSize(null);
      resetFileInput();
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {previewUrl && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          data-testid="avatar-preview"
          src={previewUrl}
          alt=""
          className="h-16 w-16 rounded-full border border-neutral-200 dark:border-neutral-800 object-cover"
        />
      )}

      <input
        ref={fileInputRef}
        data-testid="avatar-file-input"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        onChange={handleFileChange}
        className="text-sm"
      />

      {localError && (
        <p data-testid="avatar-upload-error" className="text-sm text-red-600">
          {localError}
        </p>
      )}

      {imageUrl && (
        <div className="flex flex-col gap-2">
          <div className="relative inline-block max-w-xs">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              ref={imgRef}
              data-testid="avatar-crop-image"
              src={imageUrl}
              alt=""
              onLoad={handleImageLoad}
              className="max-w-xs"
            />
            {crop && naturalSize && (
              <div
                data-testid="avatar-crop-frame"
                data-crop-x={crop.x}
                data-crop-y={crop.y}
                data-crop-size={crop.size}
                data-natural-width={naturalSize.width}
                data-natural-height={naturalSize.height}
                className="pointer-events-none absolute border-2 border-blue-500"
                style={{
                  left: `${(crop.x / naturalSize.width) * 100}%`,
                  top: `${(crop.y / naturalSize.height) * 100}%`,
                  width: `${(crop.size / naturalSize.width) * 100}%`,
                  height: `${(crop.size / naturalSize.height) * 100}%`,
                }}
              />
            )}
          </div>
          <button
            type="button"
            data-testid="avatar-crop-confirm"
            onClick={handleConfirm}
            disabled={pending || !crop}
            className="self-start rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "アップロード中…" : "この内容でアバターとして保存"}
          </button>
        </div>
      )}
    </div>
  );
}
