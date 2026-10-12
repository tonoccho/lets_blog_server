# 同じ PC の lets_blog_server_extensions へつなぐ(#1762)

`seiji/lets_blog_server_extensions`(以下 extensions)は ComfyUI・Ollama・PlantUML などを compose の
プロジェクト名 `lbx` で動かし、外部 Docker ネットワーク `lbx-shared` に別名で参加している。
同じ PC で動かすときは、このネットワークへ LBS の ai / media / platform を参加させて名前で届かせる。
別ホストの extensions へは公開 URL でつなぐ(#1763)。

| 別名(`lbx-shared` 上) | 用途 |
| --- | --- |
| `lbx-ollama:11434` | LLM(OpenAI 互換は `/v1`) |
| `lbx-comfyui:8188` | 画像生成 |
| `lbx-plantuml:8080` | PlantUML の描画 |
| `lbx-portal:3100` | extensions の portal(接続先の確認用) |

## 1. extensions を起動する

extensions のリポジトリで、共有ネットワークを作ってから起動する(スクリプトは extensions 側にある)。

```bash
scripts/create-shared-network.sh
docker compose -p lbx up -d
```

## 2. オーバーレイ付きで LBS を起動する

```bash
docker compose -f docker-compose.yml -f docker-compose.extensions.yml up -d
# docker-compose.shared-host.yml と併用する場合
docker compose -f docker-compose.yml -f docker-compose.shared-host.yml -f docker-compose.extensions.yml up -d
```

- 参加するのは ai / media / platform だけ。既存の `lbs-net`(platform は `docker-socket-proxy-net` も)は保つ。
- ネットワーク名を変えるときは環境変数 `LBX_SHARED_NETWORK`(既定 `lbx-shared`)を設定する。
- media と platform の `PLANTUML_BASE_URL` は `http://lbx-plantuml:8080` になる。
- `lbx-shared` が無いホストでは重ねないこと(重ねなければ今までどおり起動する)。
  受け入れテスト環境のスクリプトはこのオーバーレイを使わない。

## 3. システム設定に接続先を入れる

管理画面 → システム設定:

- `llm_ollama_base_url` = `http://lbx-ollama:11434/v1`
- `comfyui_base_url` = `http://lbx-comfyui:8188`

PlantUML はオーバーレイが環境変数で設定するので入力は要らない。

## 4. 別名のない名前を使わない理由

extensions のコンテナは `ollama` / `comfyui` / `plantuml` という別名を持たない。LBS 自身も同梱をやめたので、
これらの名前は解決できない。必ず `lbx-` 付きの別名を使う。

## 5. プロジェクト単位の上書きでは指定できない

プロジェクト単位の接続先の上書きは `ConnectionDestinationGuard` が docker の内部アドレスを拒否するため、
`lbx-*` は指定できない。`lbx-*` はシステム設定でだけ使う(システム設定は同検査を通らない)。

## 6. 確かめ方

- extensions の接続先一覧: LBS のコンテナ内から `GET http://lbx-portal:3100/api/connections`
  (例: `docker compose exec platform curl -s http://lbx-portal:3100/api/connections`)。
- 管理画面の「連携サービスの状況」で LLM・ComfyUI・PlantUML が正常と表示されること。PlantUML のプレビューが描画されること。

関連: [EXTERNAL_OLLAMA.md](EXTERNAL_OLLAMA.md) / [EXTERNAL_COMFYUI.md](EXTERNAL_COMFYUI.md) /
[DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)
