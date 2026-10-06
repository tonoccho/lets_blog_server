# Docker Compose 構成ガイド

マイクロサービス化(#551)にあたり、`docker-compose.yml` を将来のサービス追加(gateway /
Keycloak / 各ドメインサービス)を見据えた構成に整えた(#556)。このIssue時点では
サービス定義の実体(gateway等)は追加していない。**構成の枠組みとリソース実測**が対象。

## 共通テンプレート(YAMLアンカー)

`docker-compose.yml` の先頭で、各サービスが繰り返し必要とする設定を `x-*` 拡張フィールドに
まとめ、`<<:` マージキーで参照する。

| アンカー | 内容 | 対象 |
|---|---|---|
| `x-common-service` | `restart: unless-stopped` + `networks: [lbs-net]` | 全サービス |
| `x-actuator-healthcheck` | `curl -f http://localhost:8080/actuator/health` によるヘルスチェック | Spring Bootサービス(`api`, `log-writer`。将来の各ドメインサービスもこれに従う) |
| `x-rabbitmq-env` | `RABBITMQ_HOST` / `PORT` / `USER` / `PASSWORD` | RabbitMQに接続する全サービス |

新しいサービスを追加する際は、まずこれらのアンカーを再利用できないか検討する。

## ポート割当

内部ポートは全サービス `8080` に統一する(既に8080で立てているサービスはそのまま、
`log-writer` は本Issueで `8081`→`8080` に変更した)。外部への公開は `reverse-proxy`(nginx)
のみが行い、他のサービスは `lbs-net` 内部からのみ到達可能とする。

| サービス | 内部ポート | 外部公開 | 備考 |
|---|---|---|---|
| `reverse-proxy` (nginx) | 80, 443 | ○ (80, 443) | 唯一の外部窓口 |
| `web` | 3000 | reverse-proxy経由のみ | |
| `gateway` | 8080 | reverse-proxy経由のみ(`/api/`) | #560。JWT検証・レート制限・相関ID・下流ルーティングを一手に引き受けるAPIゲートウェイ。`/actuator/health`で下流9サービスの状態を集約(#743) |
| `identity` | 8080 | gateway経由のみ(`/api/identity/`, `/api/users/`, `/api/roles/`, `/api/auth/`, `/api/project-users`) | #561。ユーザー・ロール・権限・プロジェクトメンバー・著者マッピング・初回セットアップ。専用スキーマ `lbs_identity`(#786) |
| `project` | 8080 | gateway経由のみ(`/api/projects/`, `/api/sites/`, `/api/ssh-key-pairs/`) | #577。プロジェクト・サイト・SSH鍵・タグデザイン・GitHubトークン。`lbs_project` |
| `content` | 8080 | gateway経由のみ(`/api/posts/`, `/api/custom-tags/`, `/api/content-cache/`) | #576。投稿・カスタムタグ・組み込みタグ展開・CSSセレクタ接頭辞。`lbs_content` |
| `media` | 8080 | gateway経由のみ(`/api/generated-images/`, `/api/diagrams/`, `/api/ai/image`) | #573、#583。画像生成・生成画像・ダイアグラム・画像設定。`lbs_media` |
| `ai` | 8080 | gateway経由のみ(`/api/ai/`, `/api/generation-jobs/`) | #574。LLM生成・記事プラン・生成ジョブ。`lbs_ai` |
| `analytics` | 8080 | gateway経由のみ(`/api/projects/*/dashboard/`) | #578。GA/AdSense のレポートと資格情報。`lbs_analytics` |
| `publishing` | 8080 | gateway経由のみ(`/api/posts/publish`, `/api/taxonomy/resolve`) | #707〜#712。公開・削除・一括管理・記事プレビュー。`lbs_publishing` |
| `platform` | 8080 | gateway経由のみ(`/api/system/`, `/api/backup/`, `/api/dashboard/`) | #693〜#696。システム設定・バックアップ・ダッシュボード状態・VSCode拡張配布。`lbs_platform` |
| `log-writer` | 8080 | gateway経由のみ(`/api/logs/`)+ RabbitMQコンシューマー | 旧8081から統一。監査/操作/エラーログ。`lbs_log` |
| `mysql` | 3306 | 非公開 | |
| `rabbitmq` | 5672 (+管理UI 15672) | 非公開 | 管理UIは platform-service のキュー滞留・DLQ監視も使う(#589) |
| `phpmyadmin` | 80 | reverse-proxy経由のみ(`/phpmyadmin/`) | **認証あり(phpMyAdmin自身のログイン画面)**。`.env` の `MYSQL_USER` / `MYSQL_PASSWORD` を入力してログインする。コンテナへ `PMA_USER` / `PMA_PASSWORD` は渡さない(#978。渡すと `auth_type` が `config` になり無認証で全DBを操作できる) |
| `keycloak` | 8080(管理/ヘルスチェックは9000) | reverse-proxy経由のみ(`/auth/`) | `KC_HTTP_RELATIVE_PATH=/auth`。#559 |
| `keycloak-postgres` | 5432 | 非公開 | Keycloak専用PostgreSQL。#559 |
| `penpot-frontend` | 8080 | `9001:8080`(直接公開。ハンドオフURL生成のため) | |
| `ollama` | 11434 | 非公開(platform/ai経由) | ローカルLLM(#1086)。GPUは**オプトイン**(下記)。`platform` が DB(`system_settings.llm_ollama_base_url`。無ければ起動時に `http://ollama:11434/v1` を書き込む)で接続先を解決し、ai-service がその設定で呼ぶ(環境変数 `LLM_OLLAMA_BASE_URL` は廃止した、#1567)。reverse-proxy は中継しない |
| `ollama-model-init` | — | — | 既定モデルを起動時に取得するワンショット(#1086)。`restart: "no"` で終了コード0なら `ContainerStatusService`(#725)が「正常に完了したジョブ」として扱う |
| `comfyui` | 8188 | 非公開(media経由) | GPU必須。#979でreverse-proxyの`/comfyui/`中継を削除した(ブラウザから開く導線が無く、無認証で任意のワークフローを実行できてしまうため)。media-serviceが DB の接続先(`system_settings.comfyui_base_url`。無ければ起動時に `http://comfyui:8188` を書き込む。環境変数 `COMFYUI_BASE_URL` は廃止した、#1567)でlbs-net経由に呼ぶ |
| `plantuml` | 8080 | 非公開(content/media経由) | #979でreverse-proxyの`/plantuml/`中継を削除した(ブラウザからの参照が無く、無認証で任意のソースをサーバー側で描画させられるため)。media-serviceが`PLANTUML_BASE_URL=http://plantuml:8080`でlbs-net経由に呼ぶ |
| `drawio` | 8080 | reverse-proxy経由(`/drawio/`) | **無認証で公開する意図的な判断(#979)**。VSCode拡張の`diagramEditorPanel.ts`がwebviewのiframeへ`/drawio/?embed=1&...`を読み込む。webviewはlbs-netの外のブラウザ文脈のためコンテナ間通信に寄せられない。webからは参照しない |
| `wordpress` | 9000 | 非公開(publishing経由でプロビジョニング) | |

サービスを追加するときも、内部ポート8080・外部公開はgateway/reverse-proxy経由のみ、
という原則を踏襲する。gateway のルート表
(`services/gateway/src/main/resources/application.yml`)へエントリを足すこと。
**フォールバックは無い**(#583で廃止)ので、載せ忘れたパスは gateway が404を返す。
`RouteControllerContractTest` が「コントローラ→ルート」と「ルート→コントローラ」の
両方向を突き合わせるので、片方だけ足しても落ちる(#642 / #913)。

### phpMyAdmin の利用手順(#978)

`https://localhost/phpmyadmin/` は reverse-proxy が中継するが、アプリの認証ゲート
(ADR-0008)を通らない。そのため phpMyAdmin 自身のログインで守る。

1. `https://localhost/phpmyadmin/` を開く(ログイン画面が出る)
2. `.env` の `MYSQL_USER` / `MYSQL_PASSWORD` を入力してログインする

サービス専用スキーマ(`lbs_identity` 等)を直接見たい場合は、スキーマと同名の
サービスユーザー(`lbs_identity` など)と、`.env` の対応する `LBS_*_DB_PASSWORD` で
ログインする(`infra/mysql/init/01-create-service-schemas.sh` が作るユーザー。
詳細は [SERVICE_SCHEMA_MIGRATION.md](SERVICE_SCHEMA_MIGRATION.md))。

セッションの署名鍵(`blowfish_secret`)はコンテナ起動時に自動生成されるため、
`phpmyadmin` コンテナを再起動するとログインし直しになる。

## サービス起動順序(`depends_on` + healthcheck)

各サービスは `mysql` と `rabbitmq` の `service_healthy` を待ってから起動する。
全サービスに `x-actuator-healthcheck`(`/actuator/health`)が付いているため、
依存する側は `condition: service_healthy` で正しく待ち合わせできる。

`gateway` は下流9サービスと `keycloak` の healthy を待ってから起動する。
`web` は `gateway` の healthy を待つ。

### 起動デッドロックの解消(#668 → #583 / #786 で解消済み)

以前は `identity` が独自の Flyway を持たず、`api`(legacy-api)が管理する `lets_blog`
スキーマの存在を `ddl-auto: validate` で前提にしていた。一方 `api` は
`media`/`ai`/`content`/`analytics` の healthy を待ち、それら4サービスは `identity` の
healthy を待つ。この循環のため、空の MySQL ボリュームからの起動では `api` の Flyway 移行が
実行される機会がないまま `identity` がスキーマ検証に失敗してクラッシュループしていた。

**この循環は既に無い。**

- #786 で `identity` が専用スキーマ `lbs_identity` と自前の Flyway を持つようになり、
  他サービスのマイグレーションに依存しなくなった
- #583 で `api`(legacy-api)そのものを削除し、それ専用の一回限りジョブ
  `legacy-schema-migrate` も不要になった
- #785 で旧スキーマ `lets_blog` 自体を廃止した

現在は**全9サービスが自分のスキーマだけを Flyway で管理する**(ADR-0004)ため、
サービス間にマイグレーションの依存関係が無い。

再現手順は `scripts/verify-clean-volume-boot.sh` としてスクリプト化してある
(`lets_blog_server_mysql_data` ボリュームを削除し、クリーンな状態から
`docker compose up -d` して全サービスがhealthyになることを確認する)。

## GPU のオプトイン(#1086)

`ollama` は GPU を**オプトインで**使う。仕組みは compose の1行だけである。

```yaml
  ollama:
    runtime: ${GPU_RUNTIME:-}
```

- 既定(空)ならDockerの既定ランタイムで起動する。**NVIDIA ランタイムを持たないホストでも
  `docker compose up -d` は成功する。**
- GPU を使うホストは `.env` に `GPU_RUNTIME=nvidia` を書く。コマンドライン引数は不要で、
  `docker compose up -d` のままGPUが使われる。

`comfyui` が持つ無条件の `deploy.resources.reservations.devices` を写さないのは、それだと
NVIDIA ランタイムのないホストで `could not select device driver "nvidia"` になり、サービス
無指定の `docker compose up -d` がそこで**中断する**ためである(#1066)。#1086 で実測した
代替案の結果:

| 書き方 | 未搭載ホストでの挙動 |
| --- | --- |
| `count: ${N:-0}` | **失敗**。count が 0 でもデバイスドライバの選択は行われる |
| `gpus: ${X:-}` | **失敗**。`services.ollama.gpus value must be 'all'` で検証に落ちる |
| `runtime: ${GPU_RUNTIME:-}` | 成功。未指定は「Dockerの既定ランタイム」に倒れる |

**この節はかつて「#1066 は `comfyui` に同じ1行を適用すればよい(新しい仕組みを2つ作らない)」
と書いていたが、#1066 の実装時にその方針は変更した。** ollama と `comfyui` には決定的な
違いがある。ollama は GPU が無くてもCPUで正常に動作するのに対し、`comfyui` の既定イメージ
(`yanwk/comfyui-boot:cu130-slim`)はCUDA前提のビルドで、README.md の「ハードウェア要件」も
CPU動作には別イメージタグへの変更を要求している(#1066 の Out of Scope は ComfyUI を CPU で
動かすこと自体を除外している)。`runtime: ${GPU_RUNTIME:-}` を `comfyui` にも適用すると、
未指定時はDockerの既定ランタイムで起動を**試みてしまい**、GPUの無いホストではCUDA前提の
イメージがクラッシュし、`restart: unless-stopped` により再起動を繰り返す
(`state=running` に安定しないため、`wait-for-stack-healthy.sh --all` がタイムアウトする
おそれが残る)。

そこで `comfyui` は `runtime:` ではなく `profiles: ["gpu"]` を使う。既定の
`docker compose up -d`(サービス無指定)ではコンテナ自体を作らないため、GPUの無いホストでは
起動の試行そのものが起きない。GPUを持つホストは `.env` の `COMPOSE_PROFILES=gpu` で
オプトインする(`docker compose --profile gpu up -d` でも同じ)。コンテナが作られなければ
`docker compose ps --all` にも現れないため、`wait-for-stack-healthy.sh --all` の待機対象
からも自動的に外れる(スクリプト側の変更は不要だった)。「新しい仕組みを2つ作らない」の
判断は、ollamaとcomfyuiのCPU動作可否という前提の違いにより成り立たなかった。

## Ollama を CPU で動かす(大規模モデル)(#1396)

`GPU_RUNTIME` は「GPU が無くても起動を失敗させない」ためだけでなく、**VRAM に載らない大規模
モデルを、遅くてもシステム RAM で動かす**ための意図的な選択肢でもある。

| 選択 | `.env` | 向く用途 |
| --- | --- | --- |
| 速度優先(既定) | `GPU_RUNTIME=nvidia` | 7B クラスを VRAM で速く動かす。ComfyUI と GPU を共有する |
| 大規模モデル優先 | `GPU_RUNTIME=`(空) | VRAM(16GB 等)に載らないモデルを、システム RAM(この開発機は 125GiB)で動かす |

どちらも `docker compose up -d` のままで、追加の `-f` や引数は要らない。`ollama` の compose 定義は
1行(`runtime: ${GPU_RUNTIME:-}`)のままで、既定(`nvidia`)の挙動は変わらない。

### 手順

1. `.env` の `GPU_RUNTIME=` を空にし、`LLM_OLLAMA_MODEL` を大きなモデルへ変える(例: `qwen2.5:72b-instruct`)。
2. `docker compose up -d`。`ollama` は runtime が変わるので作り直され、`ollama-model-init` が
   新しいモデルを取得する。`ollama-model-init` は `up -d` のたびに再実行されるので、通常は
   `--force-recreate ollama-model-init` は要らない。取得済みのまま作り直したいときだけ
   `docker compose up -d --force-recreate ollama-model-init` を使う。
3. 取得の進捗は `docker logs -f lbs-ollama-model-init`。
4. 実行デバイスの確認: `docker exec lbs-ollama ollama ps` の `PROCESSOR` 列が `100% CPU` なら CPU 実行。

### 所要時間とディスクの目安

- 取得の**所要時間**は回線速度で決まる(目安: 100Mbps で 1GB あたり約 1.5 分)。既定の
  `qwen2.5:7b-instruct` は約 4.7GB で数分、72B 級の 4bit 量子化は約 40GB 台で 1 時間前後を見込む。
- **ディスク**はモデルサイズ分が `ollama_models` ボリュームに増える。モデルを切り替えても古い
  モデルは消えないので、不要なら `docker exec lbs-ollama ollama rm <model>` で消す。
- CPU 実行の**メモリ**はモデルサイズ + KV キャッシュがシステム RAM に載ること。

### GPU 前提の既定値の読み替え

`docker-compose.yml` の既定値は「単一 GPU を ComfyUI と共有する」前提で決めてある。CPU では制約が
VRAM ではなくシステム RAM と帯域に変わる。

| 変数 | GPU での意味(既定) | CPU で大規模モデルを動かすとき |
| --- | --- | --- |
| `OLLAMA_KEEP_ALIVE`(5m) | VRAM を ComfyUI に空けるため短くする | RAM は ComfyUI と取り合わないので長く(例: `30m`)して再ロードを避ける。大規模モデルは読み込み自体が遅い |
| `OLLAMA_MAX_LOADED_MODELS`(1) | VRAM の取り合いを避ける | 大規模モデルは RAM を大きく占めるので 1 のままがよい |
| `OLLAMA_CONTEXT_LENGTH`(8192) | 伸ばすほど KV キャッシュが VRAM を食う | RAM に余裕があれば伸ばせるが、伸ばすほど生成は遅くなる |

### 応答遅延とタイムアウト

CPU 実行の応答は GPU より桁違いに遅く(大規模モデルでは 1 秒あたり数トークン以下)、
`LLM_REQUEST_TIMEOUT_SECONDS`(既定 120)では足りずに失敗しうる。`.env` で
`LLM_REQUEST_TIMEOUT_SECONDS=600` など生成が終わる長さへ伸ばす。

## Ollama のモデル取得と VRAM(#1086)

モデルの初回取得は `ollama-model-init` が行う。手動の `ollama pull` は要らない。

```bash
# 取得の進捗(初回は数分。qwen2.5:7b-instruct は約4.7GB)
docker logs -f lbs-ollama-model-init
# 取得済みモデルの一覧
docker exec lbs-ollama ollama list
```

`docker compose up -d` は、正常終了済み(`exited` / `ExitCode=0` / `restart: "no"`)の
`ollama-model-init` を**毎回再実行する**(#1094 で実測: 実行前後で `FinishedAt` が
`2026-09-25T10:17:08Z` → `2026-09-25T22:14:11Z` に更新され、`Starting` / `Started` が出力された)。
`ollama pull` は冪等で、取得済みのモデルには即座に `success` を返し、モデルは変わらない。
ただし `up -d` のたびに `ollama pull` が走るため、**起動のたびにレジストリへのネットワーク
アクセスが発生する**。オフライン環境や細い回線ではこの点に注意する。

そのため、モデルを消してしまった場合も、`LLM_OLLAMA_MODEL` を変えた場合も、追加の操作は
要らない。`.env` を変えて `docker compose up -d` するだけで再取得される。
`--force-recreate ollama-model-init` は不要である。

```bash
docker compose up -d
```

取得の完了前に AI 機能を叩くと、ai-service が Ollama の 404 を
「モデル「…」がまだ利用できません。…取得の進捗は docker logs -f lbs-ollama-model-init で
確認できます。」に変換して返す(`LlmClient`)。生の 404 は表示しない。

VRAM は `lbs-comfyui` と共有する(単一GPU前提)。ComfyUI は生成後に VRAM を解放するが
(`ComfyUiClient#clearMemory`)、Ollama は既定でモデルを常駐させ続けるため、次の3つで抑える。

| 変数 | 既定 | 意味 |
| --- | --- | --- |
| `OLLAMA_KEEP_ALIVE` | `5m` | 生成後にモデルをVRAMへ残す時間。**ComfyUI 側で VRAM 不足が出るなら `30s` へ下げる** |
| `OLLAMA_MAX_LOADED_MODELS` | `1`(固定) | 同時にロードするモデル数 |
| `OLLAMA_CONTEXT_LENGTH` | `8192` | コンテキスト長。Ollama の既定は小さく、超過分は**黙って切り捨てられる**。伸ばすほど KV キャッシュが VRAM を食う |

## GPU/メモリを満たさない環境向けの縮退起動(検討結果)

**結論: 本Issueでは新たなcompose profileは導入しない。**

- GPU要件については、README記載の通り `COMFYUI_IMAGE` を CPU向けタグに切り替えることで
  既に縮退起動が可能(AI画像生成が低速になるだけで起動自体は成立する)。追加の仕組みは不要。
- メモリ制約環境向けには、Penpot関連の6サービス(`penpot-frontend` / `penpot-backend` /
  `penpot-mcp` / `penpot-exporter` / `penpot-postgres` / `penpot-valkey` /
  `penpot-mailcatch`。カスタムタグのAIデザイン生成機能でのみ使用)を
  `profiles: ["design-tools"]` として opt-in 化する案を検討した。しかし現状は
  `docker compose up -d` だけで全機能が使えることが開発者の前提になっており、
  デフォルト起動の挙動を変えると影響範囲が大きい。本Issueのスコープ(枠組み整備)を
  超えるため、今回は見送る。
- 将来的にメモリ制約環境向けの縮退起動を追加する場合は、上記Penpot 7サービスに
  `profiles: ["design-tools"]` を付与し、`docker compose --profile design-tools up -d`
  で明示的に含める形にするのが妥当(別Issueとして起票する)。

## docker-socket-proxy の権限(#1587)

`docker-socket-proxy`(platform-service だけが到達できる `docker-socket-proxy-net` 上)は、
**`GET /containers/json`、`GET /containers/<id>/json`、`POST /containers/<id>/(start|stop)` だけ**を通し、
それ以外はすべて 403 にする。#1399(ComfyUI の演算デバイス切り替え)が start / stop を必要とするため。

### なぜ環境変数では足りないか

イメージ(`tecnativa/docker-socket-proxy` v0.5.0)の既定 haproxy 設定は、先頭で
`http-request deny unless METH_GET || { env(POST) -m bool }` と全 POST を拒否してから `ALLOW_START` /
`ALLOW_STOP` を評価する。そのため次のとおり、環境変数だけでは「start / stop だけ」を作れない。

| 設定 | 結果 |
|---|---|
| `CONTAINERS=1 POST=0 ALLOW_START=1 ALLOW_STOP=1` | start / stop も 403(従来の設定に足しても効かない) |
| `CONTAINERS=1 POST=1 ALLOW_START=1 ALLOW_STOP=1` | create が 201、restart / kill / exec まで到達 |
| `CONTAINERS=0 POST=1 ALLOW_START=1 ALLOW_STOP=1` | start / stop だけ通るが `GET /containers/json` も 403 |

### 方式: frontend を差し替えた haproxy.cfg.template をマウントする

- `infra/docker-socket-proxy/haproxy.cfg.template` を保持し、`/usr/local/etc/haproxy/haproxy.cfg.template` へ
  **read-only** でマウントする。イメージのテンプレートのうち `frontend dockerfrontend` だけを差し替えた
  もので、global / defaults / backend は同一。許すものを列挙し、最後が無条件の `http-request deny`。
  環境変数(`CONTAINERS` / `POST` / `ALLOW_*`)には依存しないので、compose からは外した。
- テンプレートはイメージ内部のパスに依存するので、イメージは `:latest` ではなく**ダイジェスト固定**
  (`sha256:1f5038b54f06c3e18422902cf00ba21803d1c97805aae032e5e6673d532d3459`、ラベル
  `org.opencontainers.image.version=v0.5.0`)。更新するときは、新しいイメージの同ファイルと差分を取る。
  ```bash
  docker run --rm --entrypoint cat <image> /usr/local/etc/haproxy/haproxy.cfg.template
  ```
- `GET /containers/<id>/json` を許すのは、`ContainerStatusService` が停止中コンテナの終了コードと
  再起動ポリシーの判定(ワンショットジョブの「完了」表示)に使っているため。従来の `CONTAINERS=1` でも
  GET は通っていたので、読み取りの範囲は狭まっても、アプリが使う範囲は変わらない。
- テスト: `python3 -m unittest scripts.test_docker_socket_proxy`(ダイジェスト固定、read-only マウント、
  frontend が許可規則 3 本と最後の `deny` だけであること、許可・拒否するリクエストの例)。

### 実機での確認(2026-10-04)

使い捨てのネットワークとコンテナ(`lbs-1587-net` / `lbs-1587-target` / `lbs-1587-proxy`)に、マウント付きで
proxy を起動し、`curlimages/curl` から叩いた。共有スタックは触っていない。proxy だけが `docker.sock`
(`:ro`)を持つ。後始末済み(コンテナ 0、ネットワーク 0)。

```bash
docker network create lbs-1587-net
docker run -d --name lbs-1587-target --network lbs-1587-net busybox sleep 3600
docker run -d --name lbs-1587-proxy --network lbs-1587-net \
  -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v "$PWD/infra/docker-socket-proxy/haproxy.cfg.template":/usr/local/etc/haproxy/haproxy.cfg.template:ro \
  tecnativa/docker-socket-proxy@sha256:1f5038b54f06c3e18422902cf00ba21803d1c97805aae032e5e6673d532d3459
# 各リクエスト(例): 
docker run --rm --network lbs-1587-net curlimages/curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://lbs-1587-proxy:2375/containers/lbs-1587-target/stop
```

| リクエスト | 結果 |
|---|---|
| `GET /containers/json?all=true` | 200 |
| `GET /containers/<id>/json` | 200 |
| `POST /containers/<id>/stop` | 204 |
| `POST /containers/<id>/start` | 204 |
| `POST /containers/create` | 403(コンテナは作られなかった) |
| `DELETE /containers/<id>` | 403 |
| `POST /containers/<id>/exec` | 403 |
| `POST /images/create` | 403 |
| `POST /containers/<id>/restart` | 403 |
| `POST /containers/<id>/kill` | 403 |
| `POST /containers/<id>/pause` | 403 |
| `GET /containers/<id>/logs`、`GET /images/json` | 403 |

## コンテナログのサイズ上限(#1247)

Docker の既定 `json-file` ドライバはサイズ無制限でログを書く。2026-09-10 に `lbs-log-writer`
1 コンテナの `*-json.log` が約 352GB(約 1.46MB/秒)に達し、ホストのルートファイルシステムが
92% まで埋まった。1 サービスの暴走がホスト全体を止めないよう、全サービスに上限を付けている。

- 設定値: `driver: json-file`、`max-size: 50m`、`max-file: 5`(1 コンテナあたり最大 250MB)。
- `docker-compose.yml` は `x-logging`(アンカー `default-logging`)を各サービスが
  `logging: *default-logging` で参照する。`x-common-service` を使わないサービスにも付ける。
  `docker-compose.e2e-stubs.yml` は `x-stub-base` に同じ値を持つ。`host-tests` / `shared-host` は
  本体で定義済みのサービスの上書きだけなので、本体の設定がそのまま効く。
- 新しいサービスを足すときも付けること。`scripts/test_compose_log_rotation.py` が
  `docker compose config` の出力で全サービスを検査する。
- 設定は**コンテナの作成時**に決まる。反映には `docker compose up -d` でコンテナを作り直す。
  `docker inspect -f '{{json .HostConfig.LogConfig}}' <container>` で確認できる。
- `/etc/docker/daemon.json` の `log-opts`(デーモン既定)はリポジトリ外でホストごとに設定する。

### ログ容量の確認方法

`docker system df` はコンテナログを集計しない(Containers 欄は書き込み層のみ)ため、ログが
ディスクを食っていても見えない。ログの実体は `/var/lib/docker/containers/<id>/<id>-json.log`。
root でしか読めない環境では、読み取り専用でマウントしたコンテナ経由で測る:

```bash
docker run --rm -v /var/lib/docker/containers:/c:ro alpine sh -c 'du -sh /c/* | sort -h | tail'
docker inspect -f '{{.Name}} {{.LogPath}}' $(docker ps -aq)   # id とコンテナ名の対応
```

## media のメモリ上限(#1112)

`lbs-media` には `mem_limit: 2g` と `JAVA_TOOL_OPTIONS: -Xmx1g` を `docker-compose.yml` で明示している
(リポジトリ初のコンテナメモリ上限)。オーバーレイ(e2e-stubs / shared-host)は `media` のこれらのキーに触れない。
`mem_limit` を選んだのは、Swarm 向けの `deploy` 節に依存せず、通常の `docker compose up` で
`docker inspect` の `HostConfig.Memory` に確実に反映されるため。

### 方針

- 同期 `POST /api/ai/image` は、`batchSize`(最大16)×`batchCount`(最大16)= 最大256枚の
  Base64 文字列を、レスポンスを返し終えるまで全件オンヒープに保持する。合計枚数の上限は設けない(#1102 の決定)。
- **大きなバッチには非同期経路 `POST /api/ai/image/jobs`(#1405)を使う。** 結果は生成画像の ID で記録され、
  Base64 をヒープに積まない。
- API 契約は変えない。

### 見積もり

| 項目 | 値 |
|---|---|
| 1枚(512×512 PNG)の目安 | 約0.4 MB(Base64 で約0.5 MB) |
| 1枚(2048×2048 PNG)の目安 | 約8 MB(Base64 で約11 MB)。実画像での実測は GPU の無い環境では取れないため、PNG 圧縮率 約1.5〜2 B/px からの算出 |
| 最大構成(256枚・2048×2048)のピーク | 約2.7 GiB(Base64 文字列)+ 1枚分の `byte[]` 作業領域 + レスポンスのシリアライズ用バッファ |
| 512×512 で256枚 | 約128 MiB。`-Xmx1g` に収まる |
| 2048×2048 で収まる枚数の目安 | 約80枚(`-Xmx1g` ÷ 11 MB、シリアライズの余裕を見て) |

### 上限で足りる理由と、足りない構成

- 通常の用途(〜1024×1024 の数十枚)は `-Xmx1g` に収まる。
- **足りない構成**: 2048×2048 で概ね80枚超の同期リクエスト。この場合 `OutOfMemoryError` が起きうる。
  これは**許容したリスク**(利用者の決定、2026-10-01)で、OOM はコンテナの `mem_limit` と `-Xmx` により
  `lbs-media` 内に閉じ、ホストや他サービスのメモリには及ばない。`restart` ポリシーにより media は再起動される。
- ヒープ 1 GiB とコンテナ 2 GiB の差 1 GiB は、非ヒープ(メタスペース・スレッドスタック・ダイレクトバッファ、
  計 約0.3 GiB)と RechartsRenderer の Chromium(約0.5 GiB)を賄う余裕。アイドル時の `lbs-media` は約760 MiB(下表)。

### 確認方法

```bash
docker inspect lbs-media --format '{{.HostConfig.Memory}}'          # 2147483648
docker inspect lbs-media --format '{{.State.OOMKilled}}'            # false
curl -s http://lbs-media:8080/actuator/metrics/jvm.memory.max?tag=area:heap   # ヒープ上限(-Xmx1g 由来)
```

## リソース実測

全29コンテナを `docker compose up -d` で起動した状態で `docker stats --no-stream` を実測した値
(2026-09-01、issue #590 で再実測)。

| コンテナ | メモリ使用量 |
|---|---|
| `lbs-penpot-backend` | 3224.6 MiB |
| `lbs-web` | 1766.4 MiB |
| `lbs-comfyui` | 1422.3 MiB |
| `lbs-keycloak` | 828.7 MiB |
| `lbs-content` | 788.9 MiB |
| `lbs-media` | 759.2 MiB |
| `lbs-platform` | 576.9 MiB |
| `lbs-identity` | 572.7 MiB |
| `lbs-penpot-frontend` | 567.7 MiB |
| `lbs-log-writer` | 558.1 MiB |
| `lbs-project` | 547.6 MiB |
| `lbs-ai` | 518.5 MiB |
| `lbs-analytics` | 508.2 MiB |
| `lbs-publishing` | 464.3 MiB |
| `lbs-plantuml` | 414.6 MiB |
| `lbs-gateway` | 404.2 MiB |
| `lbs-mysql` | 396.1 MiB |
| `lbs-drawio` | 295.3 MiB |
| `lbs-penpot-exporter` | 176.9 MiB |
| `lbs-rabbitmq` | 148.6 MiB |
| `lbs-penpot-mcp` | 125.8 MiB |
| `lbs-penpot-postgres` | 108.2 MiB |
| `lbs-penpot-mailcatch` | 43.3 MiB |
| `lbs-phpmyadmin` | 42.8 MiB |
| `lbs-keycloak-postgres` | 31.3 MiB |
| `lbs-wordpress` | 27.6 MiB |
| `lbs-reverse-proxy` | 23.2 MiB |
| `lbs-docker-socket-proxy` | 20.7 MiB |
| `lbs-penpot-valkey` | 18.0 MiB |
| **合計** | **約15.0 GiB** |

**注意点**

- `web` はNext.jsの開発モード(Turbopack, ホットリロード用のbind mount)で実行しているため、
  本番ビルドより大幅にメモリを消費している。本番相当の構成にすればもっと小さくなる見込み。
- `comfyui` は画像生成をしていない状態の値。実際に生成するとGPU VRAM側の使用量が主に増加する
  (ホストメモリへの影響は本測定の範囲外)。
- ビルド時(`docker compose build` 実行中)やAI機能利用時は瞬間的にこれを上回る。
  ホストOS自体の消費分も含め、実測値(約15GiB)に対して余裕を見て**24GB以上を推奨**する(README参照)。

### 前回(#556時点)からの変化

| | コンテナ数 | 合計メモリ |
|---|---:|---:|
| #556 時点 | 19 | 約10.3 GiB |
| **現在** | **29** | **約15.0 GiB** |

増分の内訳は、Epic #551 のマイクロサービス分割で追加された9サービス
(gateway / identity / project / content / media / ai / analytics / publishing / platform、
合計約5.1 GiB)と Keycloak 一式(#559、約0.9 GiB)。
分割前の単一サービス `lbs-api`(約1.2 GiB)は #583 で削除された。

JVM が9個に増えたぶん、1サービスあたりの実処理量に比べてベースラインの消費が大きい。
メモリ制約環境では Penpot 7サービス(合計約4.3 GiB)を落とす縮退起動が最も効く
(上記「Penpotスイート」の節を参照)。

## comfyui のCPU実行構成(#1395)

nvidia デバイス予約は変数展開でもマージでも消せない(上記 #1066 の実測、`docker-compose.shared-host.yml` の
`!override` は追加の `-f` を要する)ため、CPU実行は `comfyui` とは**別サービス `comfyui-cpu`**
(`profiles: ["cpu"]`)として定義している。切り替えは `.env` の `COMPOSE_PROFILES`(`gpu` / `cpu`)だけで、
`docker compose up -d` に追加引数は要らない。

- イメージは既存の `COMFYUI_IMAGE`(CPU実行時は `yanwk/comfyui-boot:cpu`)。新しいイメージ変数は無い。
- 起動引数は環境変数 `CLI_ARGS`(コンテナ内 `/runner-scripts/entrypoint.sh` が展開)。`comfyui` は `COMFYUI_CLI_ARGS`(既定は空)、
  `comfyui-cpu` は `--cpu --force-fp32` に `COMFYUI_CLI_ARGS` を足す。
- `comfyui-cpu` はネットワークエイリアス `comfyui` を持つため、既定の接続先(`http://comfyui:8188`)は不変(`COMFYUI_BASE_URL` は廃止した、#1567)。
  固有名 `comfyui-cpu` でも到達でき、GPU構成を止めずにこちらだけを指名できる(#1401 の前提)。
- モデル・出力ボリューム(`comfyui_models` / `comfyui_output`)は共有。両者は同時に起動しない前提。
- 検証: `scripts/test_comfyui_gpu_profile.py`(compose契約テスト)。実機での生成は #1401。

## 管理画面からの演算デバイス切り替え(GPU / CPU)(#1399)

管理者は `/admin/system-settings` の「演算デバイス(ComfyUI)」で、ComfyUI を GPU 構成と CPU 構成の
どちらで動かすかを選んで適用できる。platform-service が Docker Engine API の
`POST /containers/{id}/stop` と `POST /containers/{id}/start` だけで切り替える。

- 両構成のコンテナ(`lbs-comfyui` と `lbs-comfyui-cpu`)を**事前に作っておく**。アプリは作成・削除・
  作り直しをしない。待機側の作成は運用手順で、GPU ホスト(`COMPOSE_PROFILES=gpu`)では
  `docker compose up -d` の後に一度だけ次を実行する(`.env.example` にも同じ手順を書いた)。

  ```bash
  docker compose --profile cpu create comfyui-cpu
  ```

- GPU の有無は、`lbs-comfyui` が存在するかで判定する(proxy の読み取り権限ではホストの GPU を直接
  調べられない。GPU 構成のコンテナは GPU ホストでしか作らない運用)。無ければ CPU に固定し、GPU への適用は
  API でも拒否する。`lbs-comfyui` だけがある場合は、CPU を選べない理由として上の作成手順を表示する。
- 現在の構成は、動いているコンテナ(`running`)から判定する。DB に保存した選択値は使わない。
- 適用は受け付けたらすぐ返り(202)、裏で「現在の構成を stop → 選んだ構成を start → 成功判定」を進める。
  成功は選んだ構成が `running` かつ `http://comfyui:8188/system_stats` が HTTP 200。待つ上限は
  `COMPUTE_DEVICE_APPLY_TIMEOUT_SECONDS`(既定 180)。成功しなければ選んだ構成を stop して元の構成を
  start し直し、画面に失敗の段階と理由を出す。元の構成の再起動にも失敗したときは手動復旧のコマンドを表示する。
  適用中の次の適用要求は 409 で拒否する。進行状態はメモリにだけ持つ(適用中に platform-service が
  再起動した場合の引き継ぎは対象外)。
- 向き先は `COMPUTE_DEVICE_DOCKER_BASE_URL`(既定は `DOCKER_SOCKET_PROXY_BASE_URL`)。受け入れ環境では
  これだけを Docker Engine API のスタブ(`infra/e2e-stubs/docker-engine`)へ向け、ダッシュボードの
  コンテナ一覧には影響させない。
- 注意: 運用者が手で `docker compose up -d` を実行すると、有効なプロファイル側が再び起動して**両方が動いて
  しまう場合がある**(同じネットワークエイリアス `comfyui` が重複する)。アプリはこれを自動では直さず、
  画面に「GPU と CPU の両方が稼働中」と表示する。切り替えを適用すると片方に寄せられる。
- 接続先を別ホストの ComfyUI に向けている場合(システム設定の `comfyui_base_url`)は対象外。切り替えるのは
  同じ compose で同梱しているコンテナだけである。

### Ollama の演算デバイス切り替え(#1585)

ComfyUI と同じ機構(上記)を、独立した 2 つ目の対象 `ollama` として使う。API は
`/api/system-settings/compute-devices/ollama`(参照)と `.../ollama/apply`(適用)。
管理画面では「演算デバイス(ComfyUI)」と「演算デバイス(Ollama)」の 2 欄が並び、互いのコンテナには触れない。

- **CPU 構成は別サービス `ollama-cpu`**(`container_name: lbs-ollama-cpu`)。`ollama` の runtime は
  コンテナ作成時に固定されるため、`comfyui-cpu` と同様に別サービスにする。`runtime` は指定せず、
  環境変数・ヘルスチェック(`ollama list`)・イメージは `ollama` と同じ。ネットワークエイリアス `ollama` を持つので
  接続先 `http://ollama:11434` は不変。`ollama_models` ボリュームを共有するため、GPU 構成で取得済みの
  モデルはそのまま使える(`ollama-model-init` は CPU 構成では走らせない)。
- **`profiles: ["ollama-cpu"]` であり、`cpu` には入れない**。`cpu` に入れると、GPU の無いホストで
  `COMPOSE_PROFILES=cpu` にしたとき `ollama`(runtime 空 = CPU)と二重に起動する。
- **待機側コンテナの作成は運用手順**(アプリは実行しない)。GPU ホスト(`GPU_RUNTIME=nvidia`)で、
  `docker compose up -d` の後に一度だけ次を実行する(`.env.example` にも同じ手順を書いた)。起動はしない。

  ```bash
  docker compose --profile ollama-cpu create ollama-cpu
  ```

- **GPU 構成の有無は `lbs-ollama` の `HostConfig.Runtime` が `nvidia` かで判定する**(`GET /containers/{id}/json`。
  docker-socket-proxy が既に通している読み取り)。`GPU_RUNTIME` が空のホストでも `lbs-ollama` は存在する
  (CPU 実行。#1396)ため、ComfyUI のようにコンテナの存在では判別できない。nvidia でなければ CPU 固定
  (画面は「CPU(固定)」と理由を表示し、API への GPU 適用も拒否してコンテナを start / stop しない)。
  このとき `lbs-ollama` 自身が CPU 構成として扱われる。`lbs-ollama-cpu` が無いときは、CPU を選べない理由として
  上の作成手順を表示する。
- **成功判定は「目的のコンテナが `running` かつヘルスチェックが `healthy`」**(`GET /containers/{id}/json` の
  `State.Health.Status`)。Ollama のイメージには curl が無く、URL の疎通ではなくコンテナのヘルスチェックを使う。
  切り替え・進行表示・失敗時の復帰・認可・上限時間(`COMPUTE_DEVICE_APPLY_TIMEOUT_SECONDS`)は ComfyUI と同じ。
- ダッシュボードのコンテナ一覧(#1584)は `ollama` ↔ `ollama-cpu` を待機中の組として扱い、
  稼働していない側を異常に数えない(`ContainerStatusService` の `ALTERNATIVE_PAIRS`)。
- 検証: `scripts/test_ollama_cpu_profile.py`(compose 契約)、`features/platform/compute-device.feature`
  (Ollama のシナリオ)。実機で LLM 要求が成功することの確認は #1402 の範囲である。

### docker-socket-proxy の権限(start / stop だけを開ける)— 未確定(#1587)

Epic #551 / #701 の方針(docker socket へ到達できるのは platform-service だけ)のうち、コンテナの
**start / stop だけ**を開ける。作成・削除・exec・イメージ操作・`restart`(kill を含む `ALLOW_RESTARTS`)は
開けない。当初の想定は「`POST: 0` を残し `ALLOW_START: 1` と `ALLOW_STOP: 1` だけを足す」だったが、
**実機のイメージで確認したところ成立しなかった**ため、`docker-compose.yml` の proxy の権限はまだ変更していない
(`POST: 0` のまま。この状態では start / stop は通らず、管理画面の適用は失敗として表示され元の構成のまま残る)。
方式の決定は #1587。

確認した環境: `tecnativa/docker-socket-proxy:latest`(image id `1f5038b54f06`)。使い捨てのネットワークに
proxy を起動し、docker.sock の代わりに使い捨てのスタブ socket(受けたリクエストを記録して 200/204 を返すだけ)を
マウントした。実際の docker.sock と共有スタックには触れていない。

| 設定 | `GET /containers/json` | start | stop | `POST /containers/create` | `DELETE /containers/{id}` | `POST /containers/{id}/exec` | `POST /images/create` | restart / kill / pause |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `CONTAINERS=1 POST=0 ALLOW_START=1 ALLOW_STOP=1`(当初の想定) | 200 | **403** | **403** | 403 | 403 | 403 | 403 | 403 |
| `CONTAINERS=1 POST=1 ALLOW_START=1 ALLOW_STOP=1` | 200 | 204 | 204 | **204(到達)** | **204(到達)** | **204(到達)** | 403 | **204(到達)** |
| haproxy の `frontend` を許可リストへ差し替え(下) | 200 | 204 | 204 | 403 | 403 | 403 | 403 | 403 |

原因は、イメージの `haproxy.cfg.template` の先頭が `http-request deny unless METH_GET || { env(POST) -m bool }`
で、`ALLOW_*` の allow 規則より前に評価されること。`POST=0` では全 POST がそこで拒否され、`POST=1` にすると
`CONTAINERS=1` が `/containers/` 配下の全 POST(create / exec / kill を含む)を通してしまう。

3行目は、同じイメージの `haproxy.cfg.template` の `frontend dockerfrontend` だけを次に差し替えて
read-only マウントした場合の結果で、期待どおり start / stop だけが通る(選択肢の一つとして #1587 に報告済み。
**まだ採用していない**)。

```
frontend dockerfrontend
    bind ${BIND_CONFIG}
    http-request allow if METH_GET { path,url_dec -m reg -i ^(/v[\d\.]+)?/containers/json$ }
    http-request allow if { method POST } { path,url_dec -m reg -i ^(/v[\d\.]+)?/containers/[a-zA-Z0-9_.-]+/(start|stop)$ }
    http-request deny
    default_backend dockerbackend
```

方式が決まったら、同じ手順で実機の proxy に対して再確認し、このセクションの表を更新すること。
