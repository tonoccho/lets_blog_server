# 外部AIサービス移行 調査・比較ドキュメント (issue #375)

## 背景

現在このリポジトリは、以下の3つのAI関連機能をすべて自前ホスト(Docker Compose上、GPU搭載のローカル/オンプレマシン前提)のOSSサービスに依存している。

| 機能 | 現在の実装 | 用途 |
| --- | --- | --- |
| LLM(テキスト生成) | Ollama (`ollama/ollama`イメージ) | 下書き/校正/要約支援、タグ提案、記事プランニング(壁打ちチャット)、タグデザイン生成 |
| デザイン/画像編集ハンドオフ | Penpot (self-hosted) | カスタムタグ生成時のデザインファイル作成・コメント連携 |
| 画像生成 | ComfyUI (Stable Diffusion系, GPU必須) | アイキャッチ・記事内画像の生成 |

これらはいずれもGPU付きサーバーの運用・モデルの手動pull・ディスク容量管理が必要で、運用コストが高い。本Issueはそれぞれの外部(ホスト型)サービスへの置き換えを検討し、各サービスごとに実装Issueを作成することがゴール。実装Issueは既に以下の3件が作成済み:

- #376 Replace Ollama with external LLM service provider
- #377 Replace Penpot with external design/image generation service
- #378 Replace ComfyUI with external image generation service

このドキュメントは3サービス共通の比較・意思決定記録であり、**このタイミングで実装するのは#376のみ**(#377/#378は本ドキュメントの調査結果を踏まえた上で別途着手する)。

---

## 1. LLM(Ollama代替) — 実装対象 (#376)

### 比較したアーキテクチャ選択肢

| 観点 | 特定ベンダーのSDK/APIに直接統合 | **OpenAI互換 Chat Completions API** (採用) |
| --- | --- | --- |
| 対応プロバイダ | 1社のみ | OpenAI, Groq, OpenRouter, Together AI, Fireworks, DeepInfra, Azure OpenAI 等、OpenAI互換エンドポイントを提供する全プロバイダ |
| 切り替えコスト | プロバイダ変更のたびにクライアント実装が必要 | `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` の環境変数変更のみ |
| 実装量 | ベンダーごとに異なるレスポンス形式への対応が必要 | `POST {base_url}/chat/completions` 1エンドポイントのみ |
| コスト最適化の柔軟性 | 低い(ロックイン) | 高い(用途に応じてGroq=低コスト高速、OpenAI=高品質、等を選択可能) |

→ **OpenAI互換Chat Completions APIをクライアント側の共通プロトコルとして採用**。特定ベンダーへのロックインを避けつつ、実装は単一のHTTPクライアントで完結する。

### 既定プロバイダの選定

| プロバイダ | Base URL | 特徴 |
| --- | --- | --- |
| **OpenAI (`gpt-4o-mini`)** ← 既定 | `https://api.openai.com/v1` | 品質・安定性・ドキュメント充実度で最も信頼できる。`gpt-4o-mini`は本リポジトリの用途(下書き/校正/タグ提案/壁打ちチャット)に対して十分な品質かつ低コスト。JSON mode相当の構造化出力にも対応。 |
| Groq (`llama-3.3-70b-versatile`) | `https://api.groq.com/openai/v1` | 低レイテンシ・低コストだが可用性/レート制限がOpenAIより不安定な場合がある。壁打ちチャットのような対話的用途向け。 |
| OpenRouter | `https://openrouter.ai/api/v1` | 複数モデルへの単一APIキーでのアクセス。モデル選定の柔軟性は最も高いが、レイヤーが1段増える分レイテンシがやや増える。 |

既定値はOpenAI/`gpt-4o-mini`とするが、`LLM_BASE_URL`を変更するだけで上記いずれにも切り替え可能な設計とする。

### 実装時に引き継ぐべき既存の挙動(#376実装時に反映済み)

- `OllamaClient.generate(String)` / `generate(String, String modelName)` という2つの呼び出し規約は、5つのサービス(`AiAssistService`, `CustomTagGenerationService`, `TagDesignGenerationService`, `ArticlePlanService`, プロジェクト単位のモデル解決)全体で使われている。置き換え後の`LlmClient`も同じシグネチャを維持し、呼び出し元サービスのロジック変更を最小化する。
- `<think>...</think>`ブロックの除去(`stripThinkingBlocks`)は、推論系モデル(Qwen3/DeepSeek-R1等)がOllamaの素の`/api/generate`で紛れ込ませる現象への対策。OpenAI互換APIでも一部のプロバイダ(reasoningモデルをOpenAI互換ルーティングする場合)で同様の漏れが起き得るため、防御的に同じ処理を`LlmClient`にも残す。
- 応答テキストからのJSON抽出(`extractJsonObject`/`extractJsonArray`という自由文からの緩いJSON抽出パターン)は3サービスで重複して使われており、既存のテストもこの前提に依存している。プロトコル変更(OpenAI互換API)によってこの抽出ロジック自体は変更不要(引き続きプレーンテキストとして受け取る)。
- モデルのインストール/pull/削除という概念は、ローカルOllamaサーバー特有のものでホスト型APIには存在しない。そのため`OllamaModelService`のインストール/削除ジョブ(`GenerationJob`経由の非同期実行、`ModelInstallJobRunner.runOllamaPull/runOllamaDelete`)、および`ProjectAiModelController`の`/ollama/models/install`・`DELETE /ollama/models`エンドポイント、フロントエンドの`OllamaModelTable`のインストールUIは全て削除し、「どのモデル名を使うか(自由入力+推奨候補)」を選択するだけのシンプルなUIに置き換える。
- ヘルスチェック(`ConnectedServiceStatusService`)は、Ollamaの`/api/tags`のような認証不要な疎通確認エンドポイントがホスト型APIには一般的に存在しないため、Brave Search APIと同じ「APIキーが設定されているか」を稼働状況の代わりとする方式に変更する。

### 移行時の設定変更

| 旧(Ollama) | 新(LLM) | 備考 |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `LLM_BASE_URL` | 既定値 `https://api.openai.com/v1` |
| (なし) | `LLM_API_KEY` | 新規。ホスト型APIのため必須(未設定時はヘルスチェックがWARNINGになる) |
| `OLLAMA_MODEL` | `LLM_MODEL` | 既定値 `gpt-4o-mini` |
| (なし) | `LLM_AVAILABLE_MODELS` | 新規。プロジェクト設定画面で候補表示するモデル名のカンマ区切りリスト |
| `OLLAMA_REQUEST_TIMEOUT_SECONDS` | `LLM_REQUEST_TIMEOUT_SECONDS` | 既定値 `120`(ホスト型APIはネットワーク越しのため、ローカルGPU推論より短めでも問題ない想定) |
| Docker Composeの`ollama`サービス・`ollama_models`ボリューム・nginxの`/ollama/`プロキシ | 削除 | ローカルGPUサーバー運用が不要になる |

---

## 2. デザイン/画像編集ハンドオフ(Penpot代替) — 今回は未実装、#377で対応

現在の用途は「カスタムタグ生成時に、生成に使ったプロンプトとAI応答をコメントとして添付したデザインファイルを作成する」という限定的なもの(`CustomTagGenerationService`からのベストエフォート呼び出しで、失敗してもタグ生成自体は失敗させない)。

| 選択肢 | 概要 | 所感 |
| --- | --- | --- |
| Figma REST API / Webhooks | デザインファイル作成・コメント投稿のAPIあり | 実質的な業界標準。API仕様が安定しており移行先として最有力。ただしOAuth認可フローの実装が必要。 |
| Figma (Plugin経由) | 本リポジトリには`apps/penpot-plugin/`があるのと同様、Figma Pluginでの代替も可能 | Webhook/RESTのほうがサーバーサイドから直接叩けるため優先度は下 |
| そのまま自前ホストPenpotを維持 | 現状維持 | デザインハンドオフはベストエフォート機能であり、必須要件ではない。優先度を下げて#377で個別に判断してよい |

**推奨**: Figma REST API(コメント投稿・ファイル作成)への置き換え。詳細な認可方式(サービスアカウント vs OAuth)は#377で個別調査する。

---

## 3. 画像生成(ComfyUI代替) — 今回は未実装、#378で対応

| 選択肢 | Base URL/API形式 | 所感 |
| --- | --- | --- |
| **Stability AI API** ← 有力候補 | REST API, Stable Diffusion系 | ComfyUIと同系統のモデル(SDXL等)をホスト型で利用できるため、既存のプロンプト/ネガティブプロンプト運用をほぼそのまま流用できる可能性が高い。 |
| Replicate | REST API、多数のOSSモデルをホスト | モデル選択の柔軟性が高い。コールドスタート時のレイテンシに注意。 |
| OpenAI Images API (`gpt-image-1`) | REST API | LLMをOpenAIに統一する場合、APIキー・請求を一本化できる利点があるが、既存のSD系プロンプト資産(negative prompt等)との互換性は低い。 |

**推奨**: Stability AI APIを第一候補として#378で詳細評価する(既存のプロンプト設計資産を活かせるため移行コストが最小)。

---

## 4. 意思決定サマリ(決定マトリクス)

| サービス | 対応Issue | 採用方針 | ステータス |
| --- | --- | --- | --- |
| Ollama → LLM | #376 | OpenAI互換Chat Completions API、既定プロバイダ OpenAI (`gpt-4o-mini`) | 本セッションで実装 |
| Penpot → デザインハンドオフ | #377 | Figma REST API(コメント投稿)への置き換えを推奨。認可方式は別途調査要 | 未着手(将来Issue) |
| ComfyUI → 画像生成 | #378 | Stability AI APIを第一候補として評価を推奨 | 未着手(将来Issue) |

## 5. #375 受け入れ基準との対応

- [x] Research completed for Ollama alternatives — 上記セクション1
- [x] Research completed for Penpot alternatives — 上記セクション2
- [x] Research completed for ComfyUI alternatives — 上記セクション3
- [x] Separate issues created for each service integration — #376/#377/#378(既存)
- [x] Comparison document or decision matrix created — 本ドキュメント(セクション4)
