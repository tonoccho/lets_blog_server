# 図のレンダラー(参照プラグイン)

PlantUML の図を Let's Blog に描かせるためのアダプター。
図のレンダリングの契約([CONTRACTS.md](CONTRACTS.md) §3)を話し、PlantUML サーバーの `GET /png/<エンコード>` へ翻訳する。ソースは `plugins/diagram-renderer/`(#1828)。
Recharts は同じプログラムに後から足す(#1843)。CONTRACTS.md §4.1 のとおり、図のレンダリングの登録は 1 つで、PlantUML と Recharts を 1 つのプログラムが持つ。

compose には同梱しない。**動かす場所は利用者が決める**(同じ PC・別ホスト・クラウドのどこでもよい)。

## 何をするか

| 受ける要求(契約) | 返すもの |
| --- | --- |
| `POST /render/plantuml`(`{"source":"…"}`) | PlantUML サーバーの `GET /png/<エンコード>` を呼び、`200` `image/png` を返す |
| `GET /capabilities` | `{"notations":["plantuml"]}` |

エラーは `{"error":{"type","message"}}` で返す。

| 状況 | ステータス | `type` |
| --- | --- | --- |
| `source` が空・欠落・JSON として読めない | 400 | `invalid_request` |
| JSON 本文が上限(既定 10 MiB)を超える | 413 | `payload_too_large` |
| PlantUML の記法エラー(`X-PlantUML-Diagram-Error` ヘッダーあり)。`message` は理由、分かるときは `line`(行番号) | 422 | `syntax_error` |
| PlantUML サーバーに届かない・時間切れ・5xx・ヘッダーの無い失敗 | 502 | `render_failed` |

- 認証: 受けた `Authorization` ヘッダーを、そのまま PlantUML サーバーへ転送する(extensions の `lbx-plantuml` が Keycloak のトークンを求める構成に対応。ADR-0012)。**レンダラーは秘密を持たない**。無ければ付けない。
- **再試行しない**(CONTRACTS.md §3.5)。

## 起動する

PlantUML サーバー(extensions の `lbx-plantuml`、または `plantuml/plantuml-server:jetty` を別途起動したもの)が先に必要。

### Docker

ビルドコンテキストはリポジトリのルート。

```bash
docker build -f plugins/diagram-renderer/Dockerfile -t lets-blog-diagram-renderer .
docker run -d --name diagram-renderer --restart unless-stopped -p 8080:8080 \
  -e PLANTUML_BASE_URL=http://<PlantUML サーバーのホスト>:8080 lets-blog-diagram-renderer
```

### Gradle

```bash
PLANTUML_BASE_URL=http://localhost:8081 ./gradlew :plugins:diagram-renderer:run
```

### 設定(環境変数。すべて任意)

| 変数 | 既定 | 意味 |
| --- | --- | --- |
| `PORT` | `8080` | 待ち受けるポート |
| `PLANTUML_BASE_URL` | `http://localhost:8081` | 接続先の PlantUML サーバー |
| `PLANTUML_REQUEST_TIMEOUT_SECONDS` | `30` | PlantUML サーバーへの接続・応答の待ち時間(契約の読み取り上限) |
| `RENDER_MAX_BODY_BYTES` | `10485760` | JSON 本文の上限(10 MiB) |

### 動作確認

```bash
curl -s http://<レンダラーのホスト>:8080/capabilities
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"source":"@startuml\nAlice -> Bob: hi\n@enduml"}' \
  http://<レンダラーのホスト>:8080/render/plantuml -o out.png
```

`capabilities` が `{"notations":["plantuml"]}` を返し、`out.png` が PNG になっていること。

## Let's Blog に図のレンダリングとして登録する

接続先の検査(`ConnectionDestinationGuard`、CONTRACTS.md §4.3)を通る場所に置く。Let's Blog の docker ネットワークの内側のアドレスは拒否されるので、
LAN のアドレスなど、中核から検査を通って届く宛先にすること。

1. Let's Blog の設定を開き、図のレンダリングの登録を追加する(システム全体は管理者、プロジェクト単位の上書きはプロジェクトの管理者)。
2. **種類**は図のレンダリング、**接続先 URL** にレンダラーの URL(例 `http://192.168.1.20:8080`)を入れる。`/render/plantuml` と `/capabilities` の親までを含める。
3. PlantUML サーバーが Keycloak のトークンを求めるときは、システム全体の登録に Let's Blog がツール用トークン(audience は `lbx-tools`)を付ける。レンダラーはそれを PlantUML サーバーへ転送する。
4. 保存後、能力の表示に `plantuml` が出ることを確かめる。

## 手元でテストする

```bash
./gradlew :plugins:diagram-renderer:test :plugins:diagram-renderer:jacocoTestReport
```

テストは PlantUML サーバーを模したスタブに対して、翻訳・認証の転送・記法エラー・本文の上限・接続不能を確かめる(実際の PlantUML へは接続しない)。
