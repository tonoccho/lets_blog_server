# プラグインの契約・登録・未接続時の振る舞い・棚卸し

[ADR-0011](../adr/0011-external-service-plugin-boundary.md) の決定を、後続の子 Issue(Epic #1755)がそのまま受け入れ基準に使える粒度で書く文書。
ADR は「なぜ」を、この文書は「何を話すか」を持つ(ADR の運用ルール「API 仕様は ADR に書かない」)。

- 対象は **LLM・画像生成・図のレンダリング** の3つの契約と、**プラグインの登録**(Penpot を含む)。SNS 告知(WordPress プラグイン)は対象外。
- 「現行」は 2026-10-13 時点の `develop` を読んで確認した事実。契約の「使う部分集合」は現行コードが実際に送受信するものに合わせた。
- 契約の値のうち「決定」と書いたものは本文書で決めたもの。子 Issue で変える場合は新しい ADR を作る。
- 認証(§0 の「認証」と §4.2 の認証情報の単位)は [ADR-0012](../adr/0012-extensions-tools-keycloak-auth.md)(2026-10-15)で改訂した。
- システム全体の登録の接続先の検査(§4.3)は [ADR-0013](../adr/0013-system-registration-destination-check.md)(2026-10-16)で改訂した。

## 0. 全契約に共通すること

| 項目 | 内容 |
| --- | --- |
| 接続先 | 登録した URL(§4)。エンドポイントはその URL からの相対パス。末尾の `/` は付けても付けなくてもよい |
| 認証 | 認証情報が登録されていれば `Authorization: Bearer <認証情報>` を付ける。無ければヘッダーを付けない(認証を持たないプラグインに空のヘッダーを送らない。現行の Ollama と同じ)。**例外**([ADR-0012](../adr/0012-extensions-tools-keycloak-auth.md)): 宛先がシステム設定(と `PLANTUML_BASE_URL`)の Ollama・ComfyUI・PlantUML で、`KEYCLOAK_TOOLS_CLIENT_SECRET` が設定されているときは、Keycloak(realm `letsblog`、クライアント `letsblog-tools-client`、client credentials)のトークンを Bearer で付ける。取れなければ理由の分かるエラーにし、認証なしへ落とさない。プロジェクト単位の上書き先へは送らない。既定は無効 |
| エラー応答 | HTTP の 4xx / 5xx と、OpenAI 互換のエラー本文 `{"error":{"message":"…","type":"…","code":"…"}}`。中核は `message` を利用者へ見せ、本文が無い・形が違うときは HTTP ステータスだけを見せる |
| 到達できないとき | 接続できない・タイムアウトは、中核が「プラグインに届かない」(理由と接続先つき)として扱う。現行の `ComfyUiUnreachableException`(理由と接続先つきの 502、#1126)と同じ扱いに揃える |
| 接続先の検査 | 接続のたびに `ConnectionDestinationGuard`(§4)を通し、検査したアドレスへ固定して接続する(`PinnedHttpClients`) |
| 能力の取得 | 種類ごとに §4.4 |

## 1. LLM の契約(OpenAI 互換の部分集合)

提供元は OpenAI 互換の API をそのまま話せるもの(OpenAI・Ollama の `/v1` など)。アダプターは不要。話せないもの(Anthropic Messages API など)は、
アダプターがこの契約を話して提供元へ翻訳する。

登録する URL は、`/chat/completions` と `/models` の親までを含む(OpenAI なら `https://api.openai.com/v1`、Ollama なら `http://<host>:11434/v1`)。
現行の Ollama の接続先が「OpenAI 互換の `/v1` まで含める」(`AppSettingService#getLlmOllamaBaseUrl`)のと同じ。

### 1.1 `POST {baseUrl}/chat/completions`

現行の根拠: `services/ai/src/main/java/com/letsblog/ai/ai/LlmClient.java` の `generateWithOpenAiCompatible` と `openAiUserMessage`。

リクエスト(`Content-Type: application/json`):

| フィールド | 値 |
| --- | --- |
| `model` | 文字列(必須)。呼び出し側が選んだモデル名 |
| `stream` | 常に `false` |
| `messages` | **要素は `role: "user"` の1件だけ**(下の `content` 参照) |

`messages[0].content` は次のどちらか。

- 文字列(画像なし)。
- 配列(画像1枚つき)。要素は次の2つだけ。
  - `{"type":"text","text":"<プロンプト>"}`
  - `{"type":"image_url","image_url":{"url":"data:<MIME>;base64,<データ>"}}`

レスポンス(200): `choices[0].message.content`(文字列)だけを読む。それ以外のフィールドは読まない。
中核は読んだ文字列から `<think>…</think>` ブロックを取り除き、前後の空白を削る(現行の `stripThinkingBlocks`。契約クライアントに残る)。

**この契約が使わないもの**(現行コードが送りも読みもしない。プラグインは実装しなくてよい):

- ストリーミング(`stream: true`、SSE)
- tools / function calling(`tools`、`tool_choice`、`tool_calls`)
- `system` / `assistant` / `tool` ロールのメッセージ、複数ターンの履歴
- `response_format`(JSON モード・構造化出力)
- `max_tokens` / `temperature` / `top_p` / `n` / `stop` / `logprobs` などの生成パラメータ(送らないので、プラグインが既定値を持つ。
  Anthropic Messages API は `max_tokens` が必須なので、Claude アダプターは自分の既定値を使う。現行は中核が 4096 を送っている)
- `/completions`、`/responses`、`/embeddings`、音声・画像の出力

エラー応答: §0。404 で「モデルが無い」を返すときは `error.message` にモデル名を含める(現行は Ollama の `model "…" not found` を中核が見分けて
専用の文言にしているが、この見分けは Ollama 固有なので契約クライアントへは持ち込まない。`error.message` をそのまま見せる)。

タイムアウトと再試行(現行を維持):

- 接続・読み取りとも `LLM_REQUEST_TIMEOUT_SECONDS`(ai-service の設定、既定 120 秒)。中核の呼び出し側(`SyncCallProfile.LLM`)は 3 秒 / 180 秒。
- **再試行しない**(POST。応答を捨てて重複課金させない)。

### 1.2 `GET {baseUrl}/models`

現行の根拠: `services/ai/src/main/java/com/letsblog/ai/service/ProviderModelCatalog.java`(OpenAI は `GET /v1/models` の `data[].id`)。

レスポンス(200): `{"data":[{"id":"<モデル名>"}, …]}`。中核は `data[].id` だけを読み、空文字は捨てる。**絞り込まずすべて返す**。

任意の拡張(§4.4): `data[]` の各要素に `"capabilities":{"image_input":true}` を付けてよい。付けていれば画像入力の可否の根拠になる。

- 認証: §0。
- タイムアウト: 全体で 2 秒(画面の応答時間予算 3 秒に収める。現行の `ProviderModelCatalog.DEFAULT_TIMEOUT`)。再試行しない。
- エラー・取得失敗(届かない・認証エラー・想定外の応答): 例外にせず「取得できなかった」として扱い、呼び出し側が画面へ知らせる(現行は システム設定のモデル一覧へ戻し、`fallback` で知らせる)。

## 2. 画像生成の契約(OpenAI Images API 互換 + 宣言された追加パラメータ)

利用者の回答(2026-10-11、Q3): 基本形は OpenAI Images API 互換。プラグインが**能力として宣言した追加パラメータだけ**画面に出す。
OpenAI(ChatGPT 画像生成)は直接つながる。ComfyUI などはアダプターがこの契約を話して翻訳する。

現行の根拠: `services/media/src/main/java/com/letsblog/media/ai/ChatGptImageClient.java`(`/images/generations`、参照画像ありは `/images/edits`)と、
`ImageGenerationProvider` / `ComfyUiGenerationParams`(ComfyUI 固有の指定)。

### 2.1 `POST {baseUrl}/images/generations`(参照画像なし)

リクエスト(`application/json`):

| フィールド | 値 |
| --- | --- |
| `model` | 文字列。能力が宣言したモデル(§2.3)。現行の ChatGPT は `gpt-image-1` 固定 |
| `prompt` | 文字列(必須) |
| `n` | 整数。1 回で作る枚数。能力の `maxBatchSize` 以下(現行の上限は OpenAI 10、ComfyUI 16) |
| `size` | `"<幅>x<高さ>"` または `"auto"`。能力の `sizes` に合わせる(§2.3) |
| `extra` | オブジェクト。能力が宣言した追加パラメータ(§2.3)のうち、値のあるものだけ。宣言が無ければ**送らない**(OpenAI へは付けない) |

### 2.2 `POST {baseUrl}/images/edits`(参照画像つき)

`multipart/form-data`。パートは `image`(ファイル。型と拡張子を添える)・`prompt`・`model`・`n`・`size`、宣言された追加パラメータがあるときは
`extra`(JSON 文字列)。参照画像があるときだけこちらを呼ぶ。

### 2.3 能力が宣言する追加パラメータ

ComfyUI 固有の指定(negativePrompt / steps / cfgScale / sampler / scheduler / seed / checkpoint / LoRA / loraWeight / denoise)は、
契約の本体には載らない。ComfyUI アダプターが `extra` の項目として宣言し、中核は宣言された分だけ入力欄を出す。
選択肢のある項目(チェックポイント・サンプラー・スケジューラー・LoRA)の候補は、宣言の `options` に載せる
(現行は中核が ComfyUI の `/object_info/*` を引いている)。宣言の形は §4.4。

### 2.4 レスポンス(両エンドポイント共通)

200: `{"data":[{"b64_json":"<PNG の base64>"}, …]}`。**`data[].b64_json` を必須とする**(`url` での返却は使わない)。
出力形式は **PNG**(現行は受け取った画像を `image/png` として保存する)。枚数は `n`(`edits` は参照画像から `n` 枚)。

### 2.5 エラー・タイムアウト・認証

- エラー応答: §0。現行の挙動に合わせ、失敗は「画像生成の呼び出しに失敗しました: <ステータス> <本文>」の形で利用者へ返す。
- タイムアウト: 1 回のリクエストが生成の完了まで応答を保留する(同期)。中核の待ち時間の上限は、現行の ComfyUI の最悪待ち時間
  `max(120, 60 + 8 × n)` 秒(`ComfyUiClient` の `BASE_POLL_ATTEMPTS` / `POLL_ATTEMPTS_PER_IMAGE` / `MIN_POLL_ATTEMPTS`)を契約の上限として引き継ぐ。
  nginx・gateway・media の3層の整合は `ImageGenerationTimeoutChainTest` が検査している。中核の非同期ジョブ(`POST /api/ai/image/jobs`)は変えない。
- 再試行: **しない**(GPU を消費し、重複した画像が溜まるため)。
- 認証: §0。

## 3. 図のレンダリングの契約

対象は PlantUML と Recharts。記法の解析(Markdown の表から Recharts の設定へ、`[plantuml]` タグと ```` ```plantuml ```` フェンスの検出)は中核に残り、
描画だけをプラグインが行う。

現行の根拠: `services/media/src/main/java/com/letsblog/media/controller/RenderController.java`(`POST /api/render/plantuml` / `/api/render/recharts`)、
`render/PlantUmlClient`、`render/RechartsRenderer`、`render/RechartsChartConfig`、`config/RenderBodySizeLimitFilter`。

### 3.1 `POST {baseUrl}/render/plantuml`

| | |
| --- | --- |
| リクエスト | `application/json`: `{"source":"<PlantUML の記法>"}`。`@startuml` が無い本文は中核が `@startuml` / `@enduml` で包んでから送る(現行の `PlantUmlEmbedService`)。空は 400 |
| レスポンス(200) | `Content-Type: image/png`、本文は PNG のバイト列 |
| 記法エラー | **HTTP 422**、`{"error":{"type":"syntax_error","message":"<利用者に見せられる理由>","line":<行番号。任意>}}`。中核はこの `message` を、公開では記事を止めるエラー(`InvalidPlantUmlTagException` 相当)に、プレビューでは図の位置のエラー表示に使う |
| 描画の失敗 | HTTP 5xx、`{"error":{"type":"render_failed","message":"…"}}` |
| 入力が大きすぎる | HTTP 413。上限は JSON 本文 10 MiB(現行の `app.render.max-body-bytes`) |

### 3.2 `POST {baseUrl}/render/recharts`

| | |
| --- | --- |
| リクエスト | `application/json`。チャートの設定(現行の `RechartsChartConfig`): `type`(`bar` / `line` / `area` / `pie`)、`data`(各行を `{列名: 値}` にした配列。数値列は数値化済み)、`xAxisKey`(pie は null)、`seriesKeys`、`colors`(`#RRGGBB`。空なら既定のパレット)、`stacked`、`width` / `height`(px)、`textColor` / `gridColor`(`#RRGGBB`)、`yAxisLabel`(任意) |
| レスポンス(200) | `application/json`: `{"html":"<静的な HTML / SVG(凡例を含む)>"}`。現行は `.recharts-wrapper` 要素の outerHTML。公開記事にも WebView にも JavaScript は配信されない |
| 設定エラー | HTTP 422、`{"error":{"type":"syntax_error","message":"…"}}`(例: 未知の `type`、系列が空) |
| 描画の失敗 | HTTP 5xx、`{"error":{"type":"render_failed","message":"…"}}`(現行は Chromium のタイムアウト 10 秒など) |

Markdown の表から設定を作る検証(`type` 属性の欠落・列の不一致など)は中核の `RechartsTagRenderService` に残り、これらは契約の外(プラグインへ送る前に中核が止める)。

### 3.3 `GET {baseUrl}/capabilities`

§4.4。対応する記法を宣言する。

### 3.4 draw.io の編集画面

draw.io の**編集画面**はサーバー間の契約ではなく、VS Code 拡張の WebView が iframe で開く URL である(Q4)。
図のプラグインの登録(§4.2)に任意の項目 `drawioUrl`(利用者のブラウザ / VS Code から届く URL)を持たせ、拡張はそれを開く。
サーバーは接続しないので `ConnectionDestinationGuard` の対象外(`http(s)` の URL 形式の検査だけ)。保存した SVG は中核の資産として `/api/diagrams`
(`DiagramController`)に残り、公開の停止(§5.1)の対象にしない(描画済みのため)。

### 3.5 タイムアウト・再試行・認証

- タイムアウト: 接続 3 秒・読み取り 30 秒(現行の `SyncCallProfile.RENDER`。content-service の `MediaRenderClient`)。
  publishing-service の `MediaRenderClient` は現行 20 秒だが、契約の上限は 30 秒に揃える(決定)。
- 再試行: **しない**(POST。`docs/SYNC_SERVICE_CALLS.md`)。
- 認証: §0。
- 現行の PlantUML サーバーへの呼び出し(`GET /png/<エンコード>`)は、本契約の `POST /render/plantuml` をアダプターが PlantUML サーバーへ翻訳する形に移る。

## 4. プラグインの登録

### 4.1 種類

| 種類 | 契約 | 単位 | 備考 |
| --- | --- | --- | --- |
| LLM | §1 | 複数登録でき、1つを「使用中」にする | 現行の `AiProvider`(OLLAMA / OPENAI / CLAUDE)の選択を置き換える |
| 画像生成 | §2 | 複数登録でき、1つを「使用中」にする | 現行の `ImageProvider`(COMFYUI / CHATGPT)の選択を置き換える |
| 図のレンダリング | §3 | 1つ | 対応する記法は能力で宣言(PlantUML / Recharts を1つのプラグインが持つ前提。別々のプラグインにしたければ別の登録先を用意する運用とし、中核は1つしか持たない) |
| Penpot | #1753 で決める | 1つ | 種類として名前を挙げるだけ。契約は本文書の範囲外 |

### 4.2 登録の項目と単位

| 項目 | 内容 |
| --- | --- |
| 種類 | §4.1 |
| 名前 | 画面に出す表示名(例「社内の Ollama」)。同じ種類の複数登録を見分ける |
| 接続先 URL | `http://` / `https://`。保存時に検査(§4.3。システム全体とプロジェクト単位で規則が異なる) |
| 認証情報 | 任意。`CredentialCipher` で暗号化して保存し、取得 API では値を返さず「設定済み」だけ返す |
| 使用中 | LLM・画像生成のみ。種類ごとに 1 件 |
| `drawioUrl` | 図のレンダリングのみ。任意(§3.4) |

単位は**システム全体**(管理者)と**プロジェクト単位の上書き**(プロジェクトの管理者)。解決順は **プロジェクト → システム全体 → 未接続**
(`system_settings` #1567 と、プロジェクト単位の上書き #1503 の型。環境変数へはフォールバックしない)。
プロジェクトで上書きしたら、その種類の登録は上書き側のものだけを見る(項目ごとの混ぜ合わせはしない)。

**認証情報はプロジェクト単位の登録にだけ置ける**(決定。現行の ChatGPT / Claude の API キーはプロジェクト単位だけで、システム設定へフォールバックしない
(#1568 / #1521)という規則を維持する)。システム全体の登録は、登録の項目としての認証情報を持たない。
ただし [ADR-0012](../adr/0012-extensions-tools-keycloak-auth.md) により、システム設定の宛先(Ollama・ComfyUI・PlantUML)へは、登録の項目ではなく
lets_blog の Keycloak のトークン(client credentials。§0)を付けられる(任意、既定は無効)。LAN 内で届く前提は、この任意の認証が無効のときの扱い。
プロジェクト単位の上書き先には、このトークンを送らない。この規則を緩める場合は子 Issue(登録の土台)で利用者に確かめる。

登録の管理(保存・解決・暗号化・検査・状況の問い合わせ)は platform-service に一本化し(決定)、`ai` / `media` / `content` / `publishing` は
内部 API で解決結果を読む(現行の `RemoteLlmConfigProvider` が platform のシステム設定を読むのと同じ形)。理由: 図のレンダリングは3サービス
が使い、種類ごとにサービスが保存先を持つと解決順が食い違う。保存先(現行は ai の `project_ai_settings` の `ollama_base_url` / 暗号化 API キー列、
media の `project_image_settings`、platform の `system_settings`)の移行は子 Issue の範囲。

発見元の候補: `lets_blog_server_extensions` の `GET /api/connections`(ADR-0011 §決定 5。2026-10-12)。項目は `id` / `name` / `publicUrl` / `internalUrl` /
`internalServiceUrl`(Ollama のみ `openaiBaseUrl` も)、認証なし。契約ではなく、登録先を見つける元の候補。対応づけと経路は #1762(同じ PC)・#1763(別ホスト)。

#1783 は、登録の土台(子 1)を待たずに、この発見元を**現行のシステム設定**(`llm_ollama_base_url` / `comfyui_base_url` / `penpot_base_url`)へ
取り込む形で先に実装した(管理画面の「extensions から取り込む」。管理者が「取得」で候補を見て「反映」で保存する。ポータルの URL は
`extensions_portal_url` に保存)。取り込み先を登録へ移すのは子 1(またはその後続)の範囲で、#1783 の対応づけ(`ollama` → LLM、`comfyui` → 画像生成、
`penpot` → Penpot)はそのまま登録の種類への対応づけに使える。

### 4.3 接続先の検査

規則はシステム全体の登録とプロジェクト単位の上書きで異なる([ADR-0013](../adr/0013-system-registration-destination-check.md)、2026-10-16。
このため、元の「システム全体の登録も同じ検査を通す(例外を作らない)」は改訂した)。どちらも、接続のたびにホストを名前解決し、全アドレスを検査し、
検査したアドレスへ固定して接続する(DNS rebinding の防止、#1547)。保存時にも検査する(#1518)。

- **システム全体の登録**(管理者): 禁止アドレス(ループバック・未指定・リンクローカル等。`DestinationAddressRules.isDeniedAddress`)だけを拒否する。
  docker ネットワーク内のアドレスは拒否しない(同じ PC の `lbx-*` を登録できる)。実装は platform-service の `SystemDestinationPolicy`。
  `drawioUrl`(§3.4)はサーバーが接続しないので URL 形式だけを検査する。
- **プロジェクト単位の上書き**(プロジェクトの管理者): `packages/lbs-common/src/main/java/com/letsblog/common/net/ConnectionDestinationGuard.java`。
  (a) 自プロセスが参加する docker ネットワークの範囲、(b) 禁止アドレス、のどちらにも入らないことを確かめる。
  LAN のプライベートアドレスは通る(接続先は外部の GPU PC など)。同梱コンテナが無いので、docker ネットワークへの例外にする名前は無い。

### 4.4 能力の取得

中核は、プラグインが何をできるかを**プラグイン自身に問い合わせて**知る。名前から推測しない。取得は、画面がその情報を必要としたときに行い、
結果を短時間(60 秒、決定)メモリにだけ保持する。タイムアウトは 3 秒、再試行しない。

| 種類 | 取得元 | 中身 |
| --- | --- | --- |
| LLM | `GET /models`(§1.2) | 使えるモデルの一覧(`data[].id`)。任意で各モデルの `capabilities.image_input`(画像入力の可否) |
| 画像生成 | `GET {baseUrl}/capabilities` | 下記。OpenAI へ直接つなぐ登録では `404` になるので、**組み込みの既定の能力**(モデル `gpt-image-1`、`maxBatchSize` 10、`supportsEdits: true`、`sizes: ["1024x1024","1024x1536","1536x1024"]`、追加パラメータなし)を使う |
| 図のレンダリング | `GET {baseUrl}/capabilities` | `{"notations":["plantuml","recharts"]}`。必須(`404` は能力不明=未接続扱い) |

画像生成の `/capabilities`(200)の形:

```json
{
  "models": [{"id": "…", "label": "…"}],
  "defaultModel": "…",
  "maxBatchSize": 16,
  "supportsEdits": true,
  "sizes": ["1024x1024"],
  "extraParameters": [
    {"name": "steps", "label": "ステップ数", "type": "integer", "min": 1, "max": 150, "default": 20, "appliesTo": ["generations", "edits"]},
    {"name": "sampler", "label": "サンプラー", "type": "enum", "options": ["euler"], "default": "euler"}
  ]
}
```

- `sizes` は許可するサイズの配列、または `{"min":<px>,"max":<px>,"step":<px>}`。自由な幅・高さは中核が最も近い許可サイズへ丸める(現行の `resolveSize` と同じ)。
- `type` は `integer` / `number` / `string` / `enum` / `boolean`。宣言が無い項目は画面に出さず、`extra` へも入れない。

**`VisionSupport` のモデル名推定を置き換えるか**(決定): 置き換える。画像入力の可否は、モデルの `capabilities.image_input` が宣言されていればそれを使い、
宣言が無いモデル(OpenAI・Ollama はこの拡張を返さない)だけ、モデル名の許可リスト(OpenAI / Ollama 用)を**フォールバックとして残す**。
Claude 用の分岐は、Claude アダプターが宣言するので削除する。

### 4.5 状況の表示

Web の「連携サービスの状況」は、現行の LLM・ComfyUI・PlantUML の行(と Ollama の演算デバイス `computeDevice`)を、種類ごとの行
(未登録 / 到達できる / 届かない / 能力が足りない)に置き換える。到達の確認は能力の取得(§4.4)で行う。`/api/ps` と `/system_stats` による演算デバイスの表示はやめる(Q5)。

## 5. 未接続のときの振る舞い

「未接続」は、その種類に登録が解決できない、または能力の取得で届かない(§4.5)こと。種類ごとに次のとおり。

### 5.1 図のレンダリング: CMS へ書き込む前に公開を止める(利用者の決定 2)

- 対象: 公開(`services/publishing` の `PostPublishService#publish`)で、図の記法(`[plantuml]…[/plantuml]`、```` ```plantuml ````、`[recharts …]…[/recharts]`)を含む記事。
  図を含まない記事は、未接続でも公開できる。
- **止める位置**: CMS への書き込み(メディアのアップロード・投稿の作成/更新)の**前**。カスタムタグが図のタグを生むことがあるので、検出は
  カスタムタグを展開した後の本文(content-service の `renderPreImage` の結果)で行う。登録がその記法に対応していない(能力の `notations` に無い)ときも同じ。
- **今の挙動との差**: 今は `PlantUmlEmbedService` / `PlantUmlTagRenderService` が図ごとに「レンダリング → CMS へアップロード」を繰り返すので、
  2つ目の図で失敗すると1つ目はもう CMS に書き込まれている。図の公開では、**全ての図のレンダリングを先に済ませてから**(メモリ上)、アップロードを始める
  (記法エラーも同じく、書き込みの前に止まる)。`[recharts]` は現状でも書き込み前(`renderPreImage`)に処理される。
- **エラー**: 理由の分かる文言(「図のレンダリングのプラグインが未接続です(PlantUML)。システム設定またはこのプロジェクトの設定で登録してください」)と、
  機械が読めるコード `DIAGRAM_PLUGIN_NOT_CONNECTED`、止めた記法名を返す。HTTP ステータスは記法エラー(現行 400)と区別できるものにする(子 Issue で決める)。
  VS Code 拡張は、そのメッセージを出し、設定画面を開く操作(Web の設定へのリンク)を添える。
- **保存済みの SVG**(`/api/diagrams`、draw.io)は描画済みなので、公開の停止の対象にしない。

### 5.2 図のレンダリング: プレビュー

プレビュー(`services/content` の `ArticlePreviewService#renderHtml`。VS Code のプレビューも Web も同じ HTML を使う)は止めない。
未接続の図だけを、「図のレンダリングのプラグインが未接続です(設定を開く)」という警告の箱(現行の記法エラーの箱と同じ見た目)に置き換え、
記事の残りは描画する。HTTP は 200 のまま。記法エラー(現行は記事全体をエラーの箱に置き換える)の挙動は本 Issue では変えない。
draw.io の編集画面(拡張の `diagramEditorPanel`)は、`drawioUrl` が無ければ開かず、理由と設定への案内を出す。保存済みの図の一覧・閲覧は残す。

### 5.3 LLM・画像生成

**隠さず、理由つきで無効にする**(Q6。#1753 の Requirement 4 と同じ方針)。理由は「LLM のプラグインが未接続です」/「画像生成のプラグインが未接続です」
(解決できないとき)、「届きません(接続先 …)」(届かないとき)、「このモデルは画像入力に対応していません」(能力が足りないとき)のように、**どれかを特定して**出し、
設定画面への案内(リンク)を添える。

| 画面 | 未接続のとき |
| --- | --- |
| VS Code 拡張: `askAiPanel` `sectionGenPanel` `planPanel` | パネルは開けるが、入力と送信を無効にし、理由と「設定を開く」を出す |
| VS Code 拡張: `proofreadDiagnostics`(編集中に自動で動く校正) | 編集のたびに通知を出さない。無効であることを状態表示 1 か所に出す |
| VS Code 拡張: `imageGenPanel` | 生成を無効にし、理由を出す。追加パラメータの入力欄は能力が宣言したものだけを出す |
| Web: 記事レビュー・計画・画像ギャラリーの生成・プロジェクトの AI 設定 | 該当の操作ボタンを無効にし、理由と設定画面へのリンクを添える |
| 補助的な AI 呼び出し(画像のタグ提案・アップロード画像の自動タグ付け) | 本体の操作を失敗させず省略する(現行どおり) |

設定画面は、プロジェクト単位が「プロジェクトの AI・アセット」、システム全体が「システム設定」(管理者)。VS Code 拡張は設定を持たず、Web の設定画面を開く
(`vscode.env.openExternal` など)。

API の応答(決定): 未接続・能力不足の呼び出しは HTTP 409 と `{"code":"PLUGIN_NOT_CONNECTED","kind":"llm|image|diagram","message":"…"}` を返し、
画面はこれで状態を描く。事前に無効にするための状況の問い合わせ API は子 Issue(登録の土台)で作る。

## 6. 棚卸し(2026-10-13 の `develop`)

提供元固有のコードの移り先。「残る」は、中核の契約クライアントや中核の機能として残ること。SNS は対象外。
リンクの無い項目はこのリポジトリの相対パス。移り先の子 Issue は §7。

### 6.1 LLM(`services/ai` ほか)

| ファイル・クラス | 今の役割 | 移り先 |
| --- | --- | --- |
| `services/ai/src/main/java/com/letsblog/ai/ai/LlmClient.java` | OLLAMA / OPENAI へ OpenAI 互換、CLAUDE へ Anthropic Messages API | **残る**(契約クライアント。OpenAI 互換の `/chat/completions` だけ)。`generateWithClaude`・`ANTHROPIC_BASE_URL`・`ANTHROPIC_VERSION`・`claudeUserMessage` は**アダプターへ移る**(Claude アダプターが翻訳)。`OPENAI_BASE_URL` の固定は登録の URL に置き換わる。Ollama 専用のエラー文言(`isModelNotFound` / `modelNotFoundMessage` / `OLLAMA_URL_REQUIRED`)は**削除** |
| `ai/LlmConfigProvider.java`、`ai/RemoteLlmConfigProvider.java` | プロバイダーごとの URL・キー・モデルの解決 | **残る**(登録の解決結果を読む形に作り直す) |
| `ai/AiProvider.java` | OLLAMA / OPENAI / CLAUDE の列挙 | **削除**(登録した名前と「使用中」に置き換える) |
| `service/ProviderModelCatalog.java` | モデル一覧。Ollama は `/api/tags`、OpenAI / Anthropic は `/v1/models` | **残る**(`GET /models` だけ)。Ollama の `/api/tags` と Anthropic の `x-api-key` / `?limit=1000` の分岐は**削除** |
| `service/LlmModelService.java`、`service/ReviewStepModelService.java` | プロジェクトのモデル選択 | **残る**(一覧の取得元が契約の `GET /models` になる) |
| `ai/VisionSupport.java` | モデル名による画像入力の可否の推定 | **残る・縮小**(§4.4: 宣言を優先し、宣言が無いモデル向けのフォールバックだけ。CLAUDE 分岐は削除)。呼び出しは `service/AiAssistService.java` |
| `service/OllamaPullJobRunner.java`、`service/OllamaPullService.java`、`controller/OllamaPullController.java`(`POST /api/projects/{id}/ai-models/ollama/pull`)、`dto/PullOllamaModelRequest.java`、`dto/PullOllamaModelResponse.java`、`service/InvalidOllamaModelNameException.java`、`config/AsyncJobConfig.java` の `ollamaPullExecutor` | Ollama の `/api/pull` でモデルを取得するジョブと API | **削除**(Q5。GPU PC 側で行う) |
| `services/gateway/src/main/resources/application.yml` の `project-ai-models-ollama-pull` ルート | 上記 API の経路 | **削除** |
| `apps/web/src/app/projects/[id]/OllamaModelPullForm.tsx`、`apps/web/src/app/projects/[id]/actions.ts` と `apps/web/src/lib/apiClient.ts` の `pullOllamaModel`、`apps/web/e2e/features/ai/ollama-model-pull.feature` と `apps/web/e2e/steps/aiOllamaModelPull.steps.ts` | モデル取得の画面・サーバーアクション・受け入れテスト | **削除** |
| `controller/ProjectOpenAiApiKeyController.java`、`ProjectClaudeApiKeyController.java`、`domain/ProjectAiSettings.java` の `openai_api_key_encrypted` / `claude_api_key_encrypted` / `ollama_base_url` / `llm_provider` 列 | プロバイダーごとの API キー・URL・選択 | **登録へ移行して削除**(認証情報は登録の暗号化列へ。`brave_search_api_key_encrypted` は残る) |
| `service/AiConnectionService.java`、`controller/AiConnectionController.java`、`service/ProjectConnectionService.java`、`controller/ProjectConnectionController.java`(`/api/projects/{id}/ai-models/connections`) | プロジェクトから見た AI プロバイダー4種の接続先・利用可否 | **置き換え**(§4.5 の状況の問い合わせへ) |
| `services/platform/.../service/AppSettingService.java` の `LLM_OLLAMA_BASE_URL` / `LLM_OLLAMA_MODEL` / `LLM_PROVIDER` 系の設定 | システム全体の Ollama の URL・モデル | **登録へ移行して削除** |
| `services/platform/.../service/ConnectedServiceStatusService.java` の `checkLlm*` / `checkLlmOllama` / `resolveOllamaComputeDevice` / `ollamaComputeDeviceOf`(Ollama の `GET /api/ps`) | LLM の疎通確認と演算デバイスの表示 | 疎通は**置き換え**(§4.5)。`/api/ps` と演算デバイスは**削除**(Q5)。`dto/ConnectedServiceStatusDetailResponse.java` の `computeDevice` と `apps/web/src/app/ConnectedServiceStatusPanel.tsx` の表示も削除 |
| `ai/BraveSearchClient.java`、`service/WebSearchService.java`、`services/ai/.../github/` | Web 検索・GitHub 連携 | **中核に残る**(変更なし。Epic #1755 の Out of Scope) |
| `infra/e2e-stubs/llm/server.js` | 受け入れテスト用の LLM スタブ | **残る**(契約を話すプラグインのスタブとして) |
| `apps/web/src/app/projects/[id]/AiConnectionSection.tsx`、`ChatGptConnectionSection.tsx`、`ClaudeConnectionSection.tsx`、`LlmProviderPanel.tsx`、`LlmModelPanel.tsx`、`ProjectAiModelsPanel.tsx`、`apps/web/src/app/admin/system-settings/AppSettingsPanel.tsx` の LLM 項目 | プロバイダーごとの設定画面 | **作り直し**(登録の画面へ統合。モデル選択は残る) |
| `apps/extension/src/askAiPanel.ts`、`sectionGenPanel.ts`、`planPanel.ts`、`proofreadDiagnostics.ts` / `proofreadLogic.ts`、`config.ts` の `getConfiguredAiProvider` | LLM を使う拡張のパネル | **残る**(未接続時の表示を足す。§5.3) |

### 6.2 画像生成(`services/media` ほか)

| ファイル・クラス | 今の役割 | 移り先 |
| --- | --- | --- |
| `services/media/src/main/java/com/letsblog/media/ai/ImageGenerationProvider.java` | 画像生成の抽象(`generateImage(ComfyUiGenerationParams)`) | **残る**(契約クライアントの入口。引数は ComfyUI 固有でない汎用の形に作り直す) |
| `ai/ComfyUiGenerationParams.java` | ComfyUI 固有の指定(steps / cfgScale / sampler / scheduler / seed / checkpoint / LoRA ほか)を持つ引数 | **作り直し**(prompt / size / n / 参照画像 / `extra` の汎用の形へ。ComfyUI 固有の項目はアダプターが宣言) |
| `ai/ComfyUiClient.java`(`/prompt` `/history` `/view` `/upload/image` `/object_info/*` のワークフロー組み立て・ポーリング) | ComfyUI へ直接 | **アダプターへ移る**(ComfyUI アダプター。GPU PC で ComfyUI と並べて動かす) |
| `ai/ChatGptImageClient.java`(`/images/generations`、参照画像ありは `/images/edits`) | OpenAI の画像生成 | **残る**(契約クライアントそのもの。モデル `gpt-image-1` の固定と `resolveSize` は能力の宣言へ寄せる) |
| `ai/ComfyUiImage.java` | 生成結果(ファイル名・バイト列・MIME) | **残る**(名前を汎用に直す) |
| `ai/ComfyUiUnreachableException.java` | 到達不能(理由と接続先つき 502) | **残る**(契約クライアント共通の例外へ名前を直す) |
| `ai/DownloadProgress.java` | チェックポイントのダウンロード進捗(`#1746` 後は参照元が無い) | **削除**(未使用) |
| `ai/ImageGenerationConfigProvider.java` | ComfyUI の URL と ChatGPT のキー・URL の解決 | **残る**(登録の解決結果を読む形に作り直す) |
| `ai/ImageProvider.java`(COMFYUI(16) / CHATGPT(10)) | プロバイダーの列挙と1回の枚数の上限 | **削除**(登録と能力の `maxBatchSize` に置き換え) |
| `service/ImageModelService.java`、`controller/ProjectImageModelController.java` の `/image/provider` `/image/provider/selection` | プロバイダーの選択 | **置き換え**(登録の「使用中」) |
| `service/ComfyUiModelService.java`、`dto/ComfyUiCheckpointListResponse.java`、`dto/SelectComfyUiCheckpointRequest.java`、`ProjectImageModelController` の `/comfyui/checkpoints` `/comfyui/checkpoints/selection`、`domain/ProjectImageSettings.java` の `comfyui_checkpoint` 列 | ComfyUI のチェックポイントの一覧・選択 | **削除**(一覧は能力の追加パラメータの `options` に、選択は「追加パラメータの既定値」(プロジェクト単位)に置き換え。保存列は汎用化して移行) |
| `service/ImageGenerationService.java` の `getImageOptions` と `dto/ImageGenerationOptionsResponse.java`(checkpoints / samplers / schedulers / loras) | 画面へ返す ComfyUI の選択肢 | **置き換え**(能力が宣言した追加パラメータを返す) |
| `apps/web/src/app/projects/[id]/ComfyUiCheckpointTable.tsx`、`ImageProviderPanel.tsx`、`ProjectAssetGenerationPanel.tsx` の ComfyUI 固有の入力、`apps/web/e2e/features/media/comfyui-checkpoints.feature` / `stubs/comfyui-stub.feature` | 画面・受け入れテスト | **作り直し**(追加パラメータは宣言された分だけ出す) |
| `apps/extension/src/imageGenPanel.ts` と `apiClient.ts` の `getImageGenerationOptions` / `generateImage` | 拡張の画像生成 | **残る**(追加パラメータの出し方を宣言ベースへ。未接続の表示を足す) |
| `services/platform/.../service/AppSettingService.java` の `COMFYUI_BASE_URL`、`ConnectedServiceStatusService.java` の `checkComfyUi` / `comfyUiComputeDeviceOf`(`/system_stats`) | システム全体の URL・疎通・演算デバイス | **登録へ移行 / 置き換え / 演算デバイスは削除**(§4.5) |
| `infra/e2e-stubs/comfyui/server.js`、`infra/e2e-stubs/openai-image/server.js` | 受け入れテスト用スタブ | `openai-image` は**残る**。`comfyui`(ComfyUI のネイティブ API のスタブ)は**ComfyUI アダプターの子 Issue で扱いを決める**(アダプターのテストへ移るか、契約を話すスタブへ置き換えるか) |
| `docs/EXTERNAL_COMFYUI.md`、`docs/EXTERNAL_OLLAMA.md`(#1745 / #1746 で追加済み) | 外部の ComfyUI / Ollama への接続手順 | **残る**(登録の手順へ更新) |

### 6.3 図のレンダリング

| ファイル・クラス | 今の役割 | 移り先 |
| --- | --- | --- |
| `services/media/src/main/java/com/letsblog/media/controller/RenderController.java`、`dto/PlantUmlRenderRequest.java`、`dto/RechartsRenderResponse.java`、`config/RenderBodySizeLimitFilter.java` | `POST /api/render/plantuml` / `/api/render/recharts`(gateway 非経由の内部 API) | **削除**(契約のサーバー側はアダプターが持つ。本文の上限はアダプターへ) |
| `render/PlantUmlClient.java`、`render/PlantUmlEncoder.java`(media)、`services/platform/.../render/PlantUmlEncoder.java` | 同梱の `plantuml` コンテナへ GET `/png/<エンコード>` | **アダプターへ移る**(PlantUML アダプター)。platform のコピーは疎通確認用で、状況の置き換え(§4.5)とともに**削除** |
| `render/RechartsRenderer.java`、`render/RechartsChartConfig.java`、`render/RechartsRenderException.java`、`config/PlaywrightConfig.java`、`services/media/src/main/resources/recharts/renderer.bundle.js`・`LICENSE`、`services/media/build.gradle` の Playwright 依存 | media 内のヘッドレス Chromium による Recharts の描画 | **アダプターへ移る**(Recharts アダプター)。media から Chromium・Playwright が消える。ハーネスのソースはリポジトリに見当たらず(ビルド済みの bundle のみ)、出自の確認は Recharts アダプターの子 Issue で行う。`RechartsChartConfig` は契約の入力(§3.2)として残る(クライアント側の型) |
| `services/content/.../render/MediaRenderClient.java`、`render/RechartsRenderException.java` | media-service の `/api/render/**` を呼ぶ(接続 3 秒 / 読み取り 30 秒、再試行なし) | **残る**(契約クライアントに作り直す。宛先が media-service から登録の URL になる) |
| `services/content/.../service/PlantUmlTagRenderService.java`、`RechartsTagRenderService.java`、`PlantUmlEmbedService.java`、`InvalidPlantUmlTagException.java`、`InvalidRechartsTagException`、`ArticlePreviewService.java` | `[plantuml]` / `[recharts]` / フェンスの検出・展開(プレビュー) | **残る**(記法の解析は中核。呼び出し先が契約になり、未接続の警告の箱を足す。§5.2) |
| `services/publishing/.../render/MediaRenderClient.java`、`render/MediaRenderException.java` | 公開時の PlantUML 描画の呼び出し(接続 3 秒 / 読み取り 20 秒) | **残る**(契約クライアントに作り直す。読み取りは 30 秒へ) |
| `services/publishing/.../service/PlantUmlTagRenderService.java`、`PlantUmlEmbedService.java`、`InvalidPlantUmlTagException.java`、`PostPublishService.java` | 公開時の図の展開・CMS へのアップロード | **残る**(レンダリングを CMS 書き込みの前へ寄せ、未接続の事前確認を足す。§5.1) |
| `services/content/.../controller/InternalPublishPipelineController.java` | 公開前処理(`renderPreImage`: カスタムタグ→…→`[recharts]`) | **残る**(§5.1 の検出の元になる) |
| `services/media/.../controller/DiagramController.java`(`/api/diagrams`)とその保存(draw.io の保存済み SVG) | 保存した図の資産 | **中核に残る**(変更なし。公開の停止の対象外) |
| `apps/extension/src/diagramEditorPanel.ts`(`${getServerUrl()}/drawio/` の iframe) | draw.io の編集画面 | **残る**(開く URL を登録の `drawioUrl` にし、未接続の表示を足す) |
| `apps/extension/src/diagramGalleryPanel.ts` | 保存した図の一覧 | **中核に残る**(変更なし) |
| `docker-compose.yml` の `plantuml` / `drawio` サービス(`profiles: ["diagram"]`、#1741)と `PLANTUML_BASE_URL`(media・platform)、`infra/nginx/conf.d/default.conf` の `location /drawio/` | 同梱コンテナと中継 | PlantUML は #1784 で先に削除(`plantuml` サービスと既定の `PLANTUML_BASE_URL` を `http://lbx-plantuml:8080` へ)、残りは draw.io。**削除**(draw.io はアダプターと `drawioUrl` の登録が使えるようになってから) |
| `services/platform/.../service/ConnectedServiceStatusService.java` の `checkPlantUml` | PlantUML の疎通確認 | **置き換え**(§4.5) |

### 6.4 公開イベント(参考)

`services/publishing/.../messaging/DomainEventPublisher#publishPostPublished` / `packages/lbs-common/.../messaging/PostPublishedEvent.java` は、
SNS 告知を WordPress プラグインのまま残すので変更しない。RabbitMQ は外部へ公開しない(ADR-0011 の代替案)。

## 7. Epic #1755 の子 Issue(計画)

本文書のマージ後に、次の単位で起票する(1行の範囲は Epic #1755 の「計画中」に載せる)。依存は上から順。

1. **プラグインの登録の土台**: §4(platform-service の登録・解決・暗号化・検査・能力の取得・状況の表示)と §0。
2. **LLM の契約化**: §1 と §5.3 の LLM 分、§6.1。依存 #1745(済)・1。
3. **画像生成の契約化**: §2 と §5.3 の画像分、§6.2(ComfyUI 固有の画面・API を宣言ベースへ)。依存 #1746(済)・#1747・1。
4. **図のレンダリングの契約化と公開の停止**: §3・§5.1・§5.2・§6.3(レンダリングを CMS 書き込みの前へ寄せる)。依存 1。
5. **接続先の自動設定**(`/api/connections`): ADR-0011 §決定 5。依存 1。経路は #1762・#1763。**#1783 で現行のシステム設定へ取り込む形で先に実装済み**(§4.2 末尾)。登録の土台ができたら、取り込み先を登録へ移す作業が子 1(またはその後続)の範囲。
6. **外部 Penpot**: #1753 の結論をこの登録に乗せる。依存 1。
7. **参照プラグイン: ComfyUI アダプター**: §2 のサーバー側。依存 3。
8. **参照プラグイン: Claude アダプター**: §1 のサーバー側(Anthropic Messages API へ翻訳)。依存 2。
9. **参照プラグイン: PlantUML のレンダラー**: §3.1 のサーバー側。依存 4。
10. **参照プラグイン: Recharts のレンダラー**: §3.2 のサーバー側(media から Chromium を移す)。依存 4。
11. **同梱の `drawio` の撤去**: §6.3 の最後の行。PlantUML は #1784 で先に削除、残りは draw.io。依存 4・9・10。

LLM・画像生成・図の「プラグインが無いときの画面」(Epic の計画中 5)は、2〜4 の各 Issue に含める(各 Issue がそれぞれの画面を持つため。独立させない)。
アダプターの置き場所の分類(`.claude/hooks/paths.py`)とテスト・カバレッジの扱いは、7〜10 の各 Issue の範囲(Q2)。
