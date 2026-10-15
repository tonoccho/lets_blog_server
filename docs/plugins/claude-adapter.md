# Claude アダプター(参照プラグイン)

Claude(Anthropic Messages API)を Let's Blog の LLM として使うためのアダプター。
LLM の契約([CONTRACTS.md](CONTRACTS.md) §1)を話し、Anthropic の API へ翻訳する。ソースは `plugins/claude-adapter/`(#1812)。

compose には同梱しない。**動かす場所は利用者が決める**(同じ PC・別ホスト・クラウドのどこでもよい)。

## 何をするか

| 受ける要求(契約) | 呼ぶ Anthropic の API |
| --- | --- |
| `POST /chat/completions`(user 1 件。content は文字列、または text と data URI の画像 1 枚) | `POST /v1/messages`(`max_tokens` 4096、`anthropic-version: 2023-06-01`。画像は base64 の image ブロック) |
| `GET /models` | `GET /v1/models?limit=1000`。各モデルに `capabilities.image_input` を付けて返す(`claude-1` / `claude-2` / `claude-instant` は `false`、ほかの `claude-` は `true`) |

- 認証: 受けた `Authorization: Bearer <値>` を、そのまま Anthropic の API キー(`x-api-key`)として送る。**アダプターは秘密を持たない**。Bearer が無い要求は 401。
- エラー: Anthropic のエラーは同じ HTTP ステータスで、`{"error":{"message","type","code"}}`(OpenAI 互換)にして返す。Anthropic に届かない・時間切れは 502。**再試行しない**。
- 使わないもの(ストリーミング・tools・複数ターン)は実装していない(CONTRACTS.md §1.1)。

## 起動する

### Docker

ビルドコンテキストはリポジトリのルート。

```bash
docker build -f plugins/claude-adapter/Dockerfile -t lets-blog-claude-adapter .
docker run -d --name claude-adapter --restart unless-stopped -p 8080:8080 lets-blog-claude-adapter
```

### Gradle

```bash
./gradlew :plugins:claude-adapter:run
```

### 設定(環境変数。すべて任意)

| 変数 | 既定 | 意味 |
| --- | --- | --- |
| `PORT` | `8080` | 待ち受けるポート |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Anthropic の接続先(プロキシ経由にするとき) |
| `LLM_REQUEST_TIMEOUT_SECONDS` | `120` | Anthropic への接続・応答の待ち時間 |

### 動作確認

自分の Anthropic API キーで、モデル一覧が返ること。

```bash
curl -s -H "Authorization: Bearer sk-ant-..." http://<アダプターのホスト>:8080/models
```

Bearer を付けないと 401 になる。

## Let's Blog に LLM として登録する

接続先の検査(`ConnectionDestinationGuard`、CONTRACTS.md §4.3)を通る場所に置く。Let's Blog の docker ネットワークの内側のアドレスは拒否されるので、
LAN のアドレスなど、中核から検査を通って届く宛先にすること。

1. Let's Blog のプロジェクトの設定を開き、LLM の登録(プロジェクト単位)を追加する。
2. **種類**は LLM、**接続先 URL** にアダプターの URL(例 `http://192.168.1.20:8080`)を入れる。`/chat/completions` と `/models` の親までを含める(`/v1` を付けても受ける)。
3. **認証情報**に Anthropic の API キー(`sk-ant-...`)を入れる。中核がこれを `Authorization: Bearer` として送り、アダプターが `x-api-key` として Anthropic へ渡す。
   キーはプロジェクト単位でだけ持つ(CONTRACTS.md §4.2)。システム全体の登録には入れられない。
4. 保存後、状況の表示でモデル一覧が取得できること(`claude-...` のモデルが並ぶこと)を確かめ、「使用中」にする。

アダプターを別 URL へ移したときは、登録の接続先 URL を差し替えるだけでよい(キーはアダプターに残らない)。

## 手元でテストする

```bash
./gradlew :plugins:claude-adapter:test :plugins:claude-adapter:jacocoTestReport
```

テストは Anthropic を模したスタブに対して、翻訳・認証・エラー・モデル一覧を確かめる(実際の Anthropic へは接続しない)。
