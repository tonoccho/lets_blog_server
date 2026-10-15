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
| (PlantUMLは同梱しない) | 8080 | 非公開(content/media経由) | #979でreverse-proxyの`/plantuml/`中継を削除し、#1784で同梱の`plantuml`サービスも削除した。media-serviceは`PLANTUML_BASE_URL`(既定`http://lbx-plantuml:8080`。extensionsの別名)でlbs-net経由に呼ぶ。受け入れテストでは`docker-compose.e2e-stubs.yml`の`plantuml-stub`(固定のPNGを返すスタブ。別名`lbx-plantuml`。#1802)が受ける |
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

## Ollama は同梱しない(#1745)

Ollama のコンテナ・モデル取得用のワンショットコンテナ・モデル保存用ボリュームは、すべて廃止した。
Ollama は GPU 付きの別 PC で動かし、Let's Blog は管理画面に設定した URL(例: `http://192.168.1.71:11434/v1`)
へ OpenAI 互換の `/chat/completions` で接続する。GPU PC での用意(待ち受け・LAN 内限定の公開・モデル取得・推奨設定)と
既存環境の移行は [EXTERNAL_OLLAMA.md](EXTERNAL_OLLAMA.md) を参照。

接続先は DB(`system_settings.llm_ollama_base_url`)とプロジェクト単位の上書きだけで決まり、初回起動時にも
既定値は書き込まれない(未設定のまま使うと「Ollama の接続先が設定されていません」というエラーになる)。

## ComfyUI も同梱しない(#1746)

ComfyUI も同様に、コンテナ・モデル・出力用のボリュームをすべて廃止した。GPU PC で動かす ComfyUI へ URL で接続する構成で、
用意の手順と既存環境の移行は [docs/EXTERNAL_COMFYUI.md](EXTERNAL_COMFYUI.md) を参照。
接続先(`system_settings.comfyui_base_url`)にも既定値は書き込まれない。


## 周辺ツールのオプトイン起動と縮退起動(#1741)

**結論: draw.io・phpMyAdmin は compose profile のオプトイン起動にし、既定の起動
(`docker compose up -d` / `./startup.sh`、`COMPOSE_PROFILES` 未設定)では作らない。**
以前は「`docker compose up -d` だけで全機能が使える」という開発者の前提を理由に
profile 化を見送ったが、利用者の依頼(#1741、2026-10-11)でこの前提を変えた。

| profile | サービス | 用途 | アイドル時のメモリ(参考) |
|---|---|---|---|
| `diagram` | `drawio` | draw.io エディタ(PlantUML は #1784 で同梱をやめた) | 約0.35 GB |
| `db-tools` | `phpmyadmin` | `/phpmyadmin/` での DB 確認 | 約0.01〜0.04 GB |

- 有効にする: `.env` の `COMPOSE_PROFILES=diagram,db-tools`(`gpu` / `cpu` とはカンマで併記)、
  または `docker compose --profile diagram up -d`。`.env.example` に例がある。
- 受け入れテスト環境(`scripts/rebuild-acceptance-env.sh`)は `diagram` と `db-tools` を有効にして
  起動する(`AT_PROFILES`)。受け入れテストは draw.io・phpMyAdmin を使うため。PlantUML は e2e オーバーレイの `plantuml-stub`(スタブ。#1802)が `lbx-plantuml` として受ける。実物の plantuml-server は起動しない。
- 常時起動するサービスは、これらに `depends_on` しない(`scripts/test_compose_optin_profiles.py` が検査する)。
- `scripts/wait-for-stack-healthy.sh --all` は `docker compose ps --all` に出るコンテナだけを待つので、
  作られていないコンテナは待機対象から外れる。

### 無効なときの各機能の振る舞い

| 機能 | `diagram` / `db-tools` が無効なときの振る舞い |
|---|---|
| `/phpmyadmin/`(`db-tools`) | nginx は変数の upstream(`set $upstream_phpmyadmin`)と `resolver 127.0.0.11` で中継するので、起動自体は失敗しない。要求すると名前解決に失敗し、nginx 既定の **502 Bad Gateway** ページを返す(使い捨ての nginx で再現して確認)。スタックトレースは出ない。 |
| `/drawio/`(`diagram`) | 同上。502 Bad Gateway。VS Code 拡張の図エディタ(iframe)は 502 のページを表示する。 |
| PlantUML 図のプレビュー・公開(同梱なし。extensions または別途起動した PlantUML サーバーが要る。#1784) | content / publishing は media の `/api/render/plantuml` を呼ぶ。media の `PlantUmlClient` は HTTP 応答のある失敗だけを 502 に変換し、接続不能(`ResourceAccessException`)は変換しないので media は 500 を返す。content / publishing はこれを `InvalidPlantUmlTagException`(HTTP 400、「PlantUML図のレンダリングに失敗しました: …」)にする。画面は応答するがメッセージは 400 で、依存先の不通と分かりにくい。コードの読解による(plantuml を止めた実機では未確認)。#1759 に起票。 |
| 管理画面「連携サービスの状況」の PlantUML 行 | 接続不能のため恒常的に「エラー」になる(コードの読解による)。意図した無効化と障害を区別できない。#1760 に起票。 |
| ダッシュボードのコンテナ一覧 | 存在するコンテナだけを列挙するので、作られていないコンテナは出ない。 |
| `ProjectAiSettingsService` の `DENIED_HOSTS` | compose のサービス名の一覧で、profile の有無に関係なく全サービス名を持つ。profile 化で食い違わない(`ProjectConnectionUrlDenylistTest` は `docker-compose.yml` の全サービスと照合する)。 |

図のレンダリングを外部サービスのプラグインにする方針(Epic #1755)と整合する。

### 将来の縮退起動

Ollama(#1745)も ComfyUI(#1746)も同梱しないので、このホストの GPU 要件は無い。
Penpot 一式は #1752 で削除したので profile 化しない(既存環境の移行手順は「Penpot を使っていた既存環境の移行(#1752)」)。

## reverse-proxy の経路図

この節は、reverse-proxy(nginx)が受け付ける経路のうち、**どの経路が外部に開いているか**を伝える
経路図である。`infra/nginx/conf.d/default.conf` の実態と一致させて維持する
(`scripts/test_docs_reverse_proxy_diagram.py` が、図に載っている経路が nginx に実在することを機械的に検査する。#1010)。
図は要約であり、内部向けの `location`(`/nginx-health` など)は載せていない。網羅的な一覧は `default.conf` を参照。

```
https://localhost/
├─ /             ← Web UI (Next.js, reverse-proxy → web:3000)
├─ /api          ← Let's Blog API (reverse-proxy → gateway:8080)
├─ /auth         ← Keycloak (reverse-proxy → keycloak:8080)
├─ /drawio       ← draw.io (reverse-proxy → drawio:8080)
├─ /phpmyadmin   ← phpMyAdmin (reverse-proxy → phpmyadmin:80)
└─ /sites        ← 管理対象 WordPress サイト (reverse-proxy → wordpress:80)
```

`/api` の中継先は `gateway` である。`api` という名前のサービスは `docker-compose.yml` に存在しない。

**外部に開いていない経路**(過去に図へ載っていたもの):

| 経路 | 現状 |
| --- | --- |
| `/ollama` | **存在しない。** Ollama コンテナごと廃止済み |
| `/comfyui` | **#979 で `location` を削除した。** ブラウザからは到達しない。ComfyUI へはサービス間通信でのみ到達する |
| `/penpot` | **#1752 で `location` を削除した。** どこにも中継されない(404) |

## Penpot を使っていた既存環境の移行(#1752)

Penpot は #1752 で、`docker-compose.yml` の6サービス(`penpot-frontend` / `penpot-backend` /
`penpot-mcp` / `penpot-exporter` / `penpot-postgres` / `penpot-valkey`)・2ボリューム(`penpot_postgres` /
`penpot_assets`)・`PENPOT_*` 環境変数・nginx の `/penpot` ごと削除した。外部の Penpot への接続は
#1753 で改めて計画する。

**ボリュームは自動では消えない。** `docker compose up -d` も `scripts/rebuild-acceptance-env.sh` も
`penpot_*` ボリュームを削除しない。残したいデータがあるか確かめ、**利用者が手で**消す。

1. 必要なら、先にバックアップを取る(コンテナは止まっていてよい。ボリューム名の接頭辞は
   `docker volume ls | grep penpot` で確かめる。既定のプロジェクト名なら `lets_blog_server_`):

   ```bash
   mkdir -p ~/penpot-backup
   for volume in lets_blog_server_penpot_postgres lets_blog_server_penpot_assets; do
     docker run --rm -v "${volume}:/data:ro" -v ~/penpot-backup:/backup alpine \
       tar czf "/backup/${volume}.tar.gz" -C /data .
   done
   ```

2. 新しい `docker-compose.yml` で、孤立した Penpot のコンテナを止めて消す
   (`.env` の `PENPOT_*` は不要になるので、あわせて消してよい。残っていても害は無い):

   ```bash
   docker compose down --remove-orphans
   docker compose up -d
   ```

3. 不要になったら、ボリュームを消す。**元に戻せない**ので、1. の退避を確かめてから行う:

   ```bash
   docker volume rm lets_blog_server_penpot_postgres lets_blog_server_penpot_assets
   ```

4. 取り込み直す場合(別途起動した Penpot へ戻す場合など)は、バックアップを
   `docker run --rm -v <新しいボリューム>:/data -v ~/penpot-backup:/backup alpine tar xzf /backup/<ファイル> -C /data`
   で展開する。

メモリの変化(既定の起動、アイドル時、`docker stats --no-stream`)は #1752 のコメントに実測値がある。

## docker-socket-proxy の権限(#1587)

`docker-socket-proxy`(platform-service だけが到達できる `docker-socket-proxy-net` 上)は、
**`GET /containers/json` と `GET /containers/<id>/json` だけ**を通し、
それ以外(start / stop を含むすべての POST、DELETE ほか)はすべて 403 にする。start / stop は #1399 / #1585
(演算デバイス切り替え)のために #1587 で開けたが、その機能を #1744 で廃止したので閉じた。

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
  frontend が許可規則 2 本と最後の `deny` だけであること(start / stop は 403)、許可・拒否するリクエストの例)。

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
| `POST /containers/<id>/stop` | 403(#1744 で拒否に変更。確認時点の #1587 では 204) |
| `POST /containers/<id>/start` | 403(同上) |
| `POST /containers/create` | 403(コンテナは作られなかった) |
| `DELETE /containers/<id>` | 403 |
| `POST /containers/<id>/exec` | 403 |
| `POST /images/create` | 403 |
| `POST /containers/<id>/restart` | 403 |
| `POST /containers/<id>/kill` | 403 |
| `POST /containers/<id>/pause` | 403 |
| `GET /containers/<id>/logs`、`GET /images/json` | 403 |

## 任意のオーバーレイ一覧

`-f` で明示的に重ねるオーバーレイ。いずれも `docker-compose.yml` の既定を変えない。

| ファイル | 目的 |
| --- | --- |
| `docker-compose.e2e-stubs.yml` | 受け入れテスト用のスタブ |
| `docker-compose.shared-host.yml` | ホストの 80/443 を他スタックと共有する(#1038) |
| `docker-compose.host-tests.yml` | ホスト上のテスト用 |
| `docker-compose.extensions.yml` | 同じ PC の lets_blog_server_extensions へ共有ネットワークで接続する(#1762)。手順は [EXTENSIONS_CONNECTION.md](EXTENSIONS_CONNECTION.md) |
| `docker-compose.extra-ca.yml` | 別ホストの lets_blog_server_extensions の自己署名証明書を ai / media / platform の JVM に信頼させる(#1763)。手順は [EXTENSIONS_CONNECTION.md](EXTENSIONS_CONNECTION.md) |

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

## lbs-web の本番ビルド(#1743)

`web` は既定で**本番ビルドのサーバー(`next start`)**として動く。以前は `next dev` で、`./apps/web:/app` を
バインドマウントしていた。`next dev` はオンデマンドのコンパイル・Turbopack のキャッシュ・ファイル監視を
メモリに抱えるうえ、ホストの `.next` を共有するため、ホストで `npm run build` すると稼働中の web と衝突して
500になることがあった。

- **ビルド**: `apps/web/Dockerfile` が `npm ci` のあと `npm run build` を実行し、`node_modules` と `.next` を
  イメージに焼き込む(方式は `output: 'standalone'` ではなく `next start`。`next.config.ts` は変えない)。
  `next build` はビルド時に `NEXT_PUBLIC_*` をバンドルへ焼き込むが、compose の `web` に `NEXT_PUBLIC_*` は無く
  (`scripts/test_compose_web_production.py` が固定)、実行時設定はすべてサーバー側の環境変数なので、ビルド時に
  渡すものは無い。`apps/web/Dockerfile` の `ENV NODE_ENV=production` は `next build` の後に置く(先だと devDependencies が入らない)。
- **起動**: `command: ["sh", "-c", "cd /app && exec npm run start"]`。`working_dir: /` のまま起動時にだけ `/app` へ入る
  (#1000。ヘルスチェックの cwd がバインドマウント内を指さない制約は、本番モードでは bind mount 自体が無いので満たされ、
  開発オーバーレイでも `working_dir: /` を保つ)。`docker-entrypoint.sh` は `LBS_WEB_MODE=production`(Dockerfile の既定)
  のとき `npm ci` も `.next` の削除もせず、非 root の `node` へ `su-exec` で権限を落として起動する。
- **反映の手順**: `apps/web` を変えたらイメージを作り直す。`./startup.sh --build`、`scripts/rebuild-acceptance-env.sh`
  (`up -d --build`)のどちらでも成り立つ。作り直さずに受け入れテストへ進むと、`scripts/check-image-freshness.py`(#1686)が
  古いイメージとして中断する。鮮度確認のソースパス表(`SERVICE_SOURCE_PATHS['web']`)と Dockerfile の `stamp` ステージの
  `COPY` は、`next build` が焼き込む `src` / `public` / `messages` / `next.config.ts` / `tsconfig.json` /
  `postcss.config.mjs` を含む同じ集合にしてある(`scripts/test_check_image_freshness.py` → `DockerfileStampStages` が照合)。
- **開発モード(オプトイン)**: ホットリロードが要るときだけ `docker-compose.dev-web.yml` を重ねる
  (`docker compose -f docker-compose.yml -f docker-compose.dev-web.yml up -d web`)。`./apps/web:/app` をマウントし、
  `npm run dev` で起動する。イメージの再ビルドは不要。手順は `README.md` にもある。
- `ヘルスチェック`(`/api/auth/csrf`)と reverse-proxy のヘルスチェック(web:3000 または gateway:8080 の TCP 到達)は
  本番・開発の両方で成り立つ。本番モードはコンパイルが走らず、起動後すぐ3000番で応答する。

### 実測(変更前 `next dev` と変更後 `next start`)

`docker stats --no-stream` の `lbs-web`。変更前はこのIssue着手時の `next dev`(コンテナ作成直後、ヘルスチェックによる
最初のコンパイル後)、変更後は同じホストで `ゼロ構築 → 起動直後` と、受け入れテスト全件(`npm run test:at`)実行中を
5 秒ごとに採った最大値。

| | アイドル | 受け入れテスト全件実行中のピーク |
|---|---:|---:|
| 変更前(`next dev`) | 386 MiB(Issue 起票時の実測は 1.04 GiB) | 2048 MiB(`mem_limit` 2g に張り付き。#1739 の実測) |
| 変更後(`next start`) | 73 MiB(ゼロ構築直後。AT 開始前は 180 MiB) | 195 MiB(`mem_limit` 2g の約 10%) |

`mem_limit: 2g` は据え置いた(Out of Scope)。変更後のピークが 2g を大きく下回るので、必要なら別 Issue で見直す。

## 全コンテナのメモリ上限(#1739)

`docker-compose.yml` の全サービス(全 profiles)に `mem_limit` を、Spring Boot 10サービスに
`JAVA_TOOL_OPTIONS: -Xmx...` を明示した。上限が無いと JVM はホストメモリの 1/4 を最大ヒープに取り、負荷時に G1 が
ヒープを手放さず膨らむ(変更前の合計は約 30 GB)。`media` の方式(#1112)を全サービスへ広げたもの。
`scripts/test_compose_memory_limits.py` が、全サービスの `mem_limit`、全 Spring Boot サービスの `-Xmx < mem_limit` を
ベース単独とオーバーレイ重ね掛けの両方で検査する(サービスを足して上限を付け忘れると落ちる)。

### 実測値と決めた上限

実測は受け入れテスト(`at-main` 全件、スタブ overlay 重ね)実行中の `docker stats` を 5 秒ごとに採った値(MiB)。
アイドルは実行開始直後、負荷時はその間の最大。

| コンテナ | アイドル | 負荷時ピーク | `mem_limit` | `-Xmx` |
|---|---:|---:|---|---|
| `lbs-web`(`next dev`、#1743 前) | 406 | 2048 | 2g | - |
| `lbs-platform` | 354 | 1059 | 1536m | 768m |
| `lbs-content` | 414 | 822 | 1536m | 768m |
| `lbs-ai` | 375 | 543 | 1g | 512m |
| `lbs-analytics` | 346 | 426 | 1g | 512m |
| `lbs-project` | 358 | 522 | 1g | 512m |
| `lbs-publishing` | 368 | 470 | 1g | 512m |
| `lbs-log-writer` | 369 | 583 | 1g | 512m |
| `lbs-gateway` | 190 | 287 | 1g | 512m |
| `lbs-identity` | 403 | 558 | 1g | 512m |
| `lbs-media`(#1112 の値を維持) | 439 | 919 | 2g | 1g |
| `lbs-keycloak` | 534 | 901 | 1536m | - |
| `lbs-wordpress` | 31 | 968 | 1g | - |
| `lbs-mysql` | 432 | 743 | 1g | - |
| `lbs-rabbitmq` | 135 | 208 | 768m | - |

残りは `reverse-proxy` / `phpmyadmin` 128m、`docker-socket-proxy` / `mailcatch` 64m、
`keycloak-postgres` 256m、
`drawio` 512m(`docker-compose.yml` を参照)。

根拠:

- JVM サービスは、ヒープ上限 +(メタスペース・スレッド・ダイレクトバッファ)の余裕を 0.5 GiB 前後見て `mem_limit` を決めた。
  `platform` / `content` はピークがヒープ 768m を使う負荷で 1 GiB 前後に達するため 1536m とした。`content` は同じコンテナで
  Playwright の Chromium も動かす。
- `wordpress` は WordPress のプロビジョニング中に 455 MiB で 512m に張り付いたため 1g に上げた(ピーク 968 MiB)。
- `web` は AT 実行中のピークが 1.5g の上限に達したため 2g に上げた。開発モード(`next dev`)の値で、
  #1743 で既定が本番ビルドになったため、変更後の実測は「lbs-web の本番ビルド」節を参照(必要なら見直す)。ピークが上限と同じ 2048 なのはページキャッシュを含むためで、
  この実行では `OOMKilled` にはならなかった。
- 実測の合計ピーク(lbs-* と e2e スタブ)は約 11.1 GiB(11,412 MiB。変更前の約 30 GB に対して)。

### 測定コマンド

```bash
# 上限の検査(契約テスト)
python3 -m unittest scripts.test_compose_memory_limits
# 負荷時: 受け入れテスト実行中に 5 秒ごとに採る
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | grep '^lbs-'
# 実行後、OOM で落ちたコンテナが無いこと(出力が空であること)
for c in $(docker ps -aq); do docker inspect --format '{{.Name}} {{.State.OOMKilled}}' $c; done | grep true
```

## リソース実測

全22コンテナを `docker compose up -d` で起動した状態で `docker stats --no-stream` を実測した値
(2026-09-01、issue #590 で再実測。Penpot 一式の6コンテナは #1752 で削除したので、その行は除いて合計を引き直した)。

| コンテナ | メモリ使用量 |
|---|---|
| `lbs-web` | 1766.4 MiB |
| `lbs-keycloak` | 828.7 MiB |
| `lbs-content` | 788.9 MiB |
| `lbs-media` | 759.2 MiB |
| `lbs-platform` | 576.9 MiB |
| `lbs-identity` | 572.7 MiB |
| `lbs-log-writer` | 558.1 MiB |
| `lbs-project` | 547.6 MiB |
| `lbs-ai` | 518.5 MiB |
| `lbs-analytics` | 508.2 MiB |
| `lbs-publishing` | 464.3 MiB |
| `lbs-plantuml`(#1784で削除。実測当時の値) | 414.6 MiB |
| `lbs-gateway` | 404.2 MiB |
| `lbs-mysql` | 396.1 MiB |
| `lbs-drawio` | 295.3 MiB |
| `lbs-rabbitmq` | 148.6 MiB |
| `lbs-mailcatch` | 43.3 MiB |
| `lbs-phpmyadmin` | 42.8 MiB |
| `lbs-keycloak-postgres` | 31.3 MiB |
| `lbs-wordpress` | 27.6 MiB |
| `lbs-reverse-proxy` | 23.2 MiB |
| `lbs-docker-socket-proxy` | 20.7 MiB |
| **合計** | **約9.5 GiB** |

**注意点**

- (2026-09-01 の測定時点)`web` はNext.jsの開発モード(Turbopack, ホットリロード用のbind mount)で実行していた。
  #1743 で既定が本番ビルド(`next start`)になり、メモリは大幅に減った(「lbs-web の本番ビルド」節の実測)。
- 上の測定は同梱の ComfyUI(約1.4 GiB)を含む時点のもの。ComfyUI は #1746 で同梱をやめたので、その分は不要になった。
- ビルド時(`docker compose build` 実行中)やAI機能利用時は瞬間的にこれを上回る。
  ホストOS自体の消費分も含め、実測値(Penpot を削除する前は約15GiB)に対して余裕を見て**24GB以上を推奨**する(README参照)。

### 前回(#556時点)からの変化

| | コンテナ数 | 合計メモリ |
|---|---:|---:|
| #556 時点 | 19 | 約10.3 GiB |
| 2026-09-01(#590) | 29 | 約15.0 GiB |
| **現在**(Penpot 一式の削除後、#1752) | **22** | **約9.5 GiB**(上の表から Penpot の6コンテナを除いた値) |

増分の内訳は、Epic #551 のマイクロサービス分割で追加された9サービス
(gateway / identity / project / content / media / ai / analytics / publishing / platform、
合計約5.1 GiB)と Keycloak 一式(#559、約0.9 GiB)。
分割前の単一サービス `lbs-api`(約1.2 GiB)は #583 で削除された。

JVM が9個に増えたぶん、1サービスあたりの実処理量に比べてベースラインの消費が大きい。
メモリ制約環境向けの周辺ツールのオプトイン起動は、上記「周辺ツールのオプトイン起動と縮退起動」を参照。

## 演算デバイス(GPU / CPU)の選択(#1744)

管理画面からの演算デバイス切り替え(#1399 ComfyUI、#1585 Ollama)は廃止した。Ollama と ComfyUI は GPU 付きの
別 PC で動かし、Let's Blog はそれらへ URL で接続する方針のため、同じホストの同梱コンテナを start / stop して
切り替える仕組みは操作対象が無く、docker-socket-proxy の start / stop 権限も不要になった。

GPU で動かすか CPU で動かすかは、GPU PC 側で Ollama / ComfyUI をどう起動するかで決める(Ollama は
`--gpus` / runtime の指定、ComfyUI は `--cpu` の有無など)。Let's Blog 側に切り替えの操作は無い。
連携サービスの状況(ダッシュボード)に出る Ollama / ComfyUI の演算デバイス表示(#1397)は、HTTP
(`/api/ps`、`/system_stats`)で取得するだけなので残している。
