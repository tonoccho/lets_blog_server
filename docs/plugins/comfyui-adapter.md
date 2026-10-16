# ComfyUI アダプター(参照プラグイン)

ComfyUI を Let's Blog の画像生成として使うためのアダプター。
画像生成の契約([CONTRACTS.md](CONTRACTS.md) §2・§4.4)を話し、ComfyUI のワークフローへ翻訳する。ソースは `plugins/comfyui-adapter/`(#1813)。

compose には同梱しない。**動かす場所は利用者が決める**。ふつうは GPU PC で、ComfyUI と並べて動かす。
チェックポイントや LoRA の導入・削除は ComfyUI 側(GPU PC)で行う([EXTERNAL_COMFYUI.md](../EXTERNAL_COMFYUI.md))。

## 何をするか

| 受ける要求(契約) | ComfyUI へ |
| --- | --- |
| `GET /capabilities` | `GET /object_info/CheckpointLoaderSimple`・`/object_info/KSampler`・`/object_info/LoraLoader` から選択肢を取り、追加パラメータを宣言して返す |
| `POST /images/generations`(JSON) | txt2img のワークフローを `POST /prompt` で投入 → `GET /history/<id>` で完了を待つ → `GET /view` で PNG を取る |
| `POST /images/edits`(multipart: `image`・`prompt`・`n`・`size`・`extra`) | 参照画像を `POST /upload/image` で送り、img2img(`LoadImage` → `ImageScale` → `VAEEncode`、`n` が 2 以上なら `RepeatLatentBatch`)のワークフローを投入 |

- 応答は `{"data":[{"b64_json":"<PNG の base64>"}, …]}`(`n` 枚)。
- `maxBatchSize` は 16、`supportsEdits` は `true`、`sizes` は `{"min":64,"max":2048,"step":8}`(幅・高さは 8 の倍数)。`size: "auto"` は 512x512。
- 同期: 生成が終わるまで応答を保留する。待ち時間の上限は `max(120, 60 + 8 × n)` 秒(`n`=1 で 120 秒、16 で 188 秒)。超えると 504。
- **再試行しない**。生成の前後で `/api/interrupt` も `/free` も呼ばない(#1747)。
- 認証: アダプター自身は `Authorization` を検査しない(ADR-0012。アダプターへの到達を絞るのは設置側の仕事)。ComfyUI へも認証を付けない。

### 追加パラメータ(`extra`)

宣言していない項目を `extra` に入れると 400。範囲と既定値は、移す前の中核の検証と同じ。

| 名前 | 型 | 範囲 | 既定値 | 備考 |
| --- | --- | --- | --- | --- |
| `negativePrompt` | string | | `low quality, blurry, watermark, text` | |
| `steps` | integer | 1〜150 | 20 | |
| `cfgScale` | number | 0〜30 | 7.0 | |
| `samplerName` | enum | `/object_info/KSampler` の `sampler_name` | `euler` | |
| `scheduler` | enum | `/object_info/KSampler` の `scheduler` | `normal` | |
| `seed` | integer | 0〜4294967295 | 無し(アダプターがランダムに決める) | |
| `checkpoint` | enum | `/object_info/CheckpointLoaderSimple` の `ckpt_name` | `COMFYUI_CHECKPOINT` | |
| `loraName` | enum | `/object_info/LoraLoader` の `lora_name`(LoraLoader が無い ComfyUI では空) | 無し(LoRA を使わない) | |
| `loraWeight` | number | 0〜2 | 1.0 | `loraName` があるときだけ効く |
| `denoise` | number | 0〜1 | 0.6 | **`edits` だけ**(`generations` に入れると 400) |

選択肢(`options`)は `/capabilities` を引くたびに ComfyUI から取る。値が選択肢に含まれるかは検査せず、ComfyUI が拒否したときは 502 になる。

### エラー

OpenAI 互換の `{"error":{"message","type","code"}}` で返す。

| 状況 | ステータス |
| --- | --- |
| 要求が不正(JSON でない・`prompt` が無い・`n` / `size` / `extra` が範囲外・宣言していない `extra`・`edits` に `image` が無い) | 400 |
| ComfyUI に届かない(接続拒否・名前解決不可・タイムアウト)、ComfyUI がエラーを返した | 502(メッセージに接続先と理由) |
| 待ち時間の上限を超えた | 504(`code: timeout`) |
| 想定外の失敗 | 500 |

## 起動する

### Docker

ビルドコンテキストはリポジトリのルート。

```bash
docker build -f plugins/comfyui-adapter/Dockerfile -t lets-blog-comfyui-adapter .
docker run -d --name comfyui-adapter --restart unless-stopped --network host \
  -e COMFYUI_BASE_URL=http://127.0.0.1:8188 -e PORT=8080 lets-blog-comfyui-adapter
```

ComfyUI と同じ GPU PC で動かすなら `--network host` が簡単(ComfyUI の `127.0.0.1:8188` へそのまま届く)。
別ホストの ComfyUI へつなぐなら `COMFYUI_BASE_URL` にそのアドレスを入れ、`-p 8080:8080` で公開する。

### Gradle

```bash
COMFYUI_BASE_URL=http://127.0.0.1:8188 ./gradlew :plugins:comfyui-adapter:run
```

### 設定(環境変数。すべて任意)

| 変数 | 既定 | 意味 |
| --- | --- | --- |
| `PORT` | `8080` | 待ち受けるポート |
| `COMFYUI_BASE_URL` | `http://127.0.0.1:8188` | 接続先の ComfyUI |
| `COMFYUI_CHECKPOINT` | `v1-5-pruned-emaonly.safetensors` | `checkpoint` を指定されなかったときに使うチェックポイント(`/capabilities` の既定値にもなる) |
| `COMFYUI_POLL_INTERVAL_MILLIS` | `1000` | `/history` を引く間隔。待ち時間の上限(秒)は間隔が 1 秒のときの値で、短くするとその分だけ上限も短くなる(テスト用) |

### 動作確認

```bash
curl -s http://<アダプターのホスト>:8080/capabilities
```

`maxBatchSize: 16` と、`extraParameters` の `checkpoint` の `options` に ComfyUI のチェックポイントが並ぶこと。
ComfyUI が止まっていると 502 になる。

## Let's Blog に画像生成として登録する

接続先の検査(`ConnectionDestinationGuard`、CONTRACTS.md §4.3)を通る場所に置く。Let's Blog の docker ネットワークの内側のアドレスは拒否されるので、
LAN のアドレスなど、中核から検査を通って届く宛先にすること。

1. Let's Blog の管理画面(システム全体のプラグイン)、またはプロジェクトの設定で、画像生成の登録を追加する。
2. **種類**は画像生成、**接続先 URL** にアダプターの URL(例 `http://192.168.1.30:8080`)を入れる。`/capabilities` と `/images/...` の親までを含む。
3. 認証情報は不要(空のままにする)。
4. 保存後、状況の表示で能力が取得できること(`checkpoint` などの入力欄が出ること)を確かめ、「使用中」にする。

アダプターを別 URL へ移したときは、登録の接続先 URL を差し替えるだけでよい。

## 手元でテストする

```bash
./gradlew :plugins:comfyui-adapter:test :plugins:comfyui-adapter:jacocoTestReport
```

テストは ComfyUI を模したスタブ(テスト内のサーバー)に対して、能力の宣言・生成・参照画像つきの生成・エラー・待ち時間の上限を確かめる
(実際の ComfyUI へは接続しない)。`infra/e2e-stubs/comfyui`(ComfyUI のネイティブ API のスタブ)は、中核の契約化(#1848)で契約を話すスタブへ置き換えるまで残す。
