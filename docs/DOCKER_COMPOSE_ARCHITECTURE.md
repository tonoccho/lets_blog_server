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
| `ollama` | 11434 | 非公開(platform/ai経由) | ローカルLLM(#1086)。GPUは**オプトイン**(下記)。`platform` が `LLM_OLLAMA_BASE_URL=http://ollama:11434/v1` を解決し、ai-service がその設定で呼ぶ。reverse-proxy は中継しない |
| `ollama-model-init` | — | — | 既定モデルを起動時に取得するワンショット(#1086)。`restart: "no"` で終了コード0なら `ContainerStatusService`(#725)が「正常に完了したジョブ」として扱う |
| `comfyui` | 8188 | 非公開(media経由) | GPU必須。#979でreverse-proxyの`/comfyui/`中継を削除した(ブラウザから開く導線が無く、無認証で任意のワークフローを実行できてしまうため)。media-serviceが`COMFYUI_BASE_URL=http://comfyui:8188`でlbs-net経由に呼ぶ |
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
- `comfyui-cpu` はネットワークエイリアス `comfyui` を持つため、`COMFYUI_BASE_URL`(`http://comfyui:8188`)は不変。
  固有名 `comfyui-cpu` でも到達でき、GPU構成を止めずにこちらだけを指名できる(#1401 の前提)。
- モデル・出力ボリューム(`comfyui_models` / `comfyui_output`)は共有。両者は同時に起動しない前提。
- 検証: `scripts/test_comfyui_gpu_profile.py`(compose契約テスト)。実機での生成は #1401。
