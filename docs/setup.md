# セットアップマニュアル

Let's Blog Server の開発環境を構築する手順。Phase 6 でリバースプロキシ(nginx)が導入され、
以降はすべてのサービスに `https://localhost` 経由でアクセスする。Web管理画面(Next.js)も
Docker Compose管理下のコンテナとして起動するため、`docker compose up -d` だけで全サービスが
立ち上がる(ホスト側で `npm run dev` を手動起動し続ける必要はない)。

## クイックスタート

前提ソフトが何も入っていない Ubuntu/Debian 系の機械であれば、`setup.sh` を1回実行すれば
`https://localhost` にアクセスできる状態まで到達する(前提ソフト導入・`.env` 生成・
TLS証明書生成・全サービス起動・healthy確認を一括で行う。詳細は
[README.md の「アプリケーションの起動(Docker)」](../README.md#アプリケーションの起動docker)
を参照)。

```bash
git clone -b develop <このリポジトリのURL>
cd lets_blog_server
./setup.sh
```

`.env` が既に存在する場合は上書きしない。既定は `develop` ブランチでの実行のみを許可する
(`--branch <name>` / `--main` で上書き可能)。

### `setup.sh` が行っている手順を個別に実行する場合

前提ソフトが既に導入済みの環境や、Ubuntu/Debian 以外の環境では、以下を手動でなぞる。

```bash
git clone -b develop <このリポジトリのURL>
cd lets_blog_server

# 1. 環境変数を設定
cp .env.example .env
vi .env   # パスワード・APIキー・暗号化キー・NEXTAUTH_SECRET等を変更
bash scripts/check-env.sh   # .env が .env.example の全項目を満たしているか確認

# 2. リバースプロキシ用の自己署名証明書を生成
bash scripts/generate-certs.sh

# 3. Docker Compose で全サービスを起動(reverse-proxy/web/api/log-writer/mysql/rabbitmq/
#    phpmyadmin/comfyui/plantuml/drawio/wordpress/Penpotスイート。計19コンテナ)
docker compose up -d --build

# 4. 全サービスがhealthyになるまで待機
bash scripts/wait-for-stack-healthy.sh --all

# 5. ブラウザで https://localhost にアクセス(自己署名証明書の警告は例外承認する)
```

## システム要件

- Docker / Docker Compose(Compose v2 系。`docker compose version` で確認)
- openssl(証明書生成に使用。Linux/macOSは標準搭載)
- NVIDIA GPU + NVIDIA Container Toolkit(ComfyUIのGPU利用に推奨。CPUのみでも動作するイメージタグに変更すれば起動は可能だが低速)
- 外部LLMサービス(既定: OpenAI)のAPIキー(下書き/校正/要約支援・タグ提案・記事プランニングに使用)
- ホストの 80番・443番ポートが空いていること(リバースプロキシが使用)

## 1. `.env` の設定

`.env.example` をコピーして `.env` を作成し、以下を変更する。

| 項目 | 説明 | 変更要否 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | MySQLのパスワード | 必須変更 |
| `APP_ENCRYPTION_KEY` | CMS認証情報暗号化キー(Base64, 32バイト)。生成例: `openssl rand -base64 32` | 必須変更 |
| `COMFYUI_IMAGE` | ComfyUIイメージ(GPU種別に応じて変更。既定はNVIDIA CUDA13系) | 環境に応じて変更 |
| `LLM_API_KEY` | 下書き/校正/要約・タグ提案・記事プランニングで使う外部LLMサービス(既定: OpenAI)のAPIキー | 必須変更 |
| `LLM_MODEL` | 使用するモデル名(既定: `gpt-4o-mini`) | 既定値のままでも可 |
| `COMFYUI_CHECKPOINT` | 画像生成に使うチェックポイントファイル名 | 既定値のままでも可 |
| `APP_MAIL_FROM` / `APP_WEB_BASE_URL` | メール送信元・Web公開URL(メール内リンク生成に使用) | `APP_WEB_BASE_URL` は `https://localhost` を指定 |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | 外部メールサービス(SendGrid/Resend/AWS SES等)のSMTP接続情報 | 必須変更 |
| `NEXTAUTH_SECRET` | Web管理画面(Auth.js)のセッション署名鍵。生成例: `openssl rand -hex 32` | 必須変更 |
| `LBS_*_DB_PASSWORD` | サービス別スキーマ用のMySQLユーザーのパスワード(ADR-0004)。`LBS_BACKUP_DB_PASSWORD` はplatform-serviceのバックアップ機能が使う `lbs_backup` ユーザー用 | 必須変更 |

### `.env` が `.env.example` に追随しているか確認する

`.env` は初回に `cp` で作るきりなので、その後 `.env.example` に項目が増えても追随しない。
追随漏れは静かに壊れる — `docker compose` は警告を出すが起動自体は成功し、
`infra/mysql/init/01-create-service-schemas.sh` は `LBS_*_DB_PASSWORD` が空だと該当ユーザーの
作成を**スキップする**(#756 ではこれで `lbs_backup` が作られず、バックアップ機能が動かなかった)。

```bash
bash scripts/check-env.sh
```

`.env.example` にあって `.env` に無いキー、および `.env` で空になっているキーを報告する。
不足があれば終了コード1で落ちる。`git pull` で `.env.example` が更新されたら実行すること。

### 重複キーの報告について（#959）

同じキーが2回以上定義されていると、このスクリプトは**終了コード1で落ちる**。

`docker compose` の `env_file` も shell の `source` も**後の定義が勝つ**。したがって
キーが2箇所にあると、コメント付きの正しい定義を書き換えても後の行に上書きされて無視される。
しかも警告は一切出ない。#959 では `.env.example` の `PENPOT_SECRET_KEY` がこれで、
「512-bit base64 で生成せよ」という指示を持たないほうが有効になっていた。

**既存の `.env` を持つ環境では、これまで通っていた検査が急に落ちることがある。**
`.env.example` を古い時点でコピーした `.env` には、同じ重複がそのまま入っている
可能性が高いためである。これは意図した挙動で、直し方は次のとおり。

1. 報告されたキーを `.env` から探す（`grep -n '^KEY=' .env`）
2. **どちらの値が現に効いているかを確認する** — 効いているのは後の行のほう
3. 残したい値を1行にまとめ、もう一方を削除する

値が同じなら片方を消すだけでよい。値が違う場合は、後の行が現在の挙動なので、
それを変えるつもりが無ければ後の行の値を残すこと。

`LBS_*_DB_PASSWORD` を後から足した場合は、MySQLのユーザー作成をやり直す必要がある。
`docker-entrypoint-initdb.d` はデータボリュームが空のときしか走らないため、
既存ボリュームでは手動で再実行する(このスクリプトは冪等)。

```bash
docker compose up -d mysql
docker compose exec mysql bash /docker-entrypoint-initdb.d/01-create-service-schemas.sh
```

詳細は [SERVICE_SCHEMA_MIGRATION.md](SERVICE_SCHEMA_MIGRATION.md) を参照。

## 2. TLS証明書の生成

リバースプロキシ(nginx)が `https://localhost` を終端するための自己署名証明書を生成する。

```bash
bash scripts/generate-certs.sh
```

- `certs/localhost.crt` / `certs/localhost.key` を生成する(初回のみ。既にあれば何もしない)
- 秘密鍵を含むため `certs/` はコミット対象外([.gitignore](../.gitignore))
- 証明書を作り直す場合は `certs/` 配下を削除してから再実行する

## 3. Docker Compose の起動

```bash
docker compose up -d
```

起動するサービス: `reverse-proxy`(nginx) / `web`(Next.js) / `api` / `log-writer` / `mysql` /
`rabbitmq` / `phpmyadmin` / `comfyui` / `plantuml` / `drawio` / `wordpress` /
`penpot-*`(デザイン生成スイート、6コンテナ)/ `docker-socket-proxy`。
アーキテクチャ・ポート割当・全サービスの起動時メモリ実測値は
[docs/DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md) を参照。

Phase 6 以降、`reverse-proxy` の `80`(HTTP→HTTPSリダイレクト)・`443`(HTTPS)以外はホストにポート公開していない。
各サービスへは直接ポートではなく、必ず `https://localhost/...` 経由でアクセスする。

サービスによってはヘルスチェックが設定されており(`api` / `log-writer` / `mysql` / `rabbitmq` /
`penpot-postgres` / `penpot-valkey`)、依存先が healthy になるまで起動を待つため、初回起動や
複数コンテナの一括再作成時は数十秒〜数分かかることがある。`docker compose ps` の `STATUS`
列が `Up` ではなく `Up (healthy)` になっているかを確認する。

`web` サービスはソースディレクトリ(`./web`)をコンテナにバインドマウントしているため、
コード変更は再ビルドなしでホットリロードされる。`package.json` の依存関係を変更した場合は
`docker compose up -d --build web` でイメージを再ビルドする。

```bash
docker compose ps           # 起動状況確認(healthyかどうかも表示される)
docker compose logs -f api  # 個別サービスのログ確認
docker compose logs -f web  # Web管理画面のログ確認
```

### 個別サービスの再起動・再ビルド

コード変更後、全サービスを再作成する必要はない。変更したサービスだけを対象にする。

```bash
# 環境変数変更など、再ビルド不要な場合
docker compose restart api

# コード変更を反映する場合(イメージの再ビルドが必要。例: content-service)
docker compose build content
docker compose up -d content
```

バックエンドの各サービスは `services/<サービス名>` のGradleビルド成果物を
イメージに焼き込む構成のため、ソース変更後は必ず `docker compose build` からやり直す
(コンテナ再起動だけでは反映されない)。

### サービス間の疎通確認

現状は `reverse-proxy`(nginx)が唯一の外部窓口。個別サービスの単体疎通確認にはコンテナ内から
直接アクセスする。

```bash
# reverse-proxy経由(通常のアクセス経路。初回セットアップ導線は未認証で叩ける)
curl -k https://localhost/api/auth/setup-status

# 個別サービスの疎通確認(コンテナ内から直接。curlは#556で追加済み)
docker exec lbs-content curl -sf http://localhost:8080/actuator/health
docker exec lbs-log-writer curl -sf http://localhost:8080/actuator/health
```

gateway は下流のバックエンドサービス9個(identity / project / content / media / ai /
analytics / publishing / platform / log-writer)の状態を自身の `/actuator/health` に
集約するため(`services/gateway/.../DownstreamHealthConfig`、#560・#743)、
次のコマンドでまとめて確認できる。

```bash
docker exec lbs-gateway curl -s http://localhost:8080/actuator/health
```

`-f` を付けないのは、いずれかが DOWN のとき gateway が 503 を返すため。
`-f` があると curl が本文を出さずに終了してしまい、**どのサービスが DOWN なのかが分からない**。
gateway は `show-details: always` なので、本文にサービスごとの状態が入っている。

mysql / rabbitmq / keycloak / web などは集約の対象外なので、個別に確認する。

なお、いずれか1つでも DOWN だと gateway 自身のヘルスも DOWN になり、
`docker ps` で `lbs-gateway (unhealthy)` と表示される。一部のサービスだけ起動している
開発中はこれが正常なので、gateway の unhealthy 表示だけを見て異常と判断しないこと
(gateway の healthy を起動条件にしているコンテナは無いため、起動順序には影響しない)。

### アクセスURL一覧

| サービス | URL | 用途 |
|---|---|---|
| Web管理画面 | https://localhost/ | サイト管理・投稿履歴・AIジョブ・ユーザー管理等(Next.js) |
| 仲介APIサーバー | https://localhost/api/ | REST API(VSCode拡張・Web管理画面が使用) |
| phpMyAdmin | https://localhost/phpmyadmin/ | MySQLデータベース管理 |
| draw.io | https://localhost/drawio/ | ダイアグラム編集UI(VSCode拡張のwebviewが読み込む。#979) |

ComfyUI と PlantUML はブラウザからは開けない(#979 で reverse-proxy の `/comfyui/` /
`/plantuml/` 中継を削除した)。いずれも media-service が `lbs-net` 経由で呼ぶ内部専用サービスで、
稼働状況はダッシュボードの「接続サービス状態」パネルで確認する。

### ブラウザの自己署名証明書警告について

`https://localhost` は自己署名証明書を使用しているため、初回アクセス時にブラウザで
「この接続ではプライバシーが保護されません」等の警告が表示される。「詳細設定」→
「localhost にアクセスする(安全ではありません)」等から例外承認して進める(表記はブラウザにより異なる)。

## 4. ComfyUIチェックポイントの配置

`comfyui_models` はDocker管理の名前付きボリュームであり、ホストの特定ディレクトリに
直接バインドされていない。チェックポイントファイルは `docker cp` でコンテナ内にコピーする。

```bash
docker cp <ダウンロードしたcheckpointファイル> lbs-comfyui:/root/ComfyUI/models/checkpoints/
```

配置後、`.env` の `COMFYUI_CHECKPOINT` にファイル名を設定し、`docker compose up -d api` で反映する。
配置確認:

```bash
docker exec lbs-comfyui ls /root/ComfyUI/models/checkpoints/
```

## 5. Web管理画面(Next.js)について

`docker compose up -d` に含まれる `web` サービスが自動的に起動する。設定は
`docker-compose.yml` の `web.environment` で以下のように渡される(`.env` の値を参照)。

| 環境変数 | 値 | 説明 |
|---|---|---|
| `LETS_BLOG_GATEWAY_URL` | `http://gateway:8080` | サーバーサイドAPI呼び出しの唯一の宛先(issue #584)。lbs-net内部でgatewayコンテナへ直接到達するため自己署名証明書を経由しない |
| `NEXTAUTH_SECRET` | `${NEXTAUTH_SECRET}` | `.env` の値 |
| `NEXTAUTH_URL` | `https://localhost` | ブラウザから見える公開URL(認証コールバック等の生成に使用) |

Web管理画面自身のサーバーサイドAPI呼び出しがコンテナ間の平文HTTP通信になるため、
Web管理画面側では自己署名証明書の信頼設定(`NODE_EXTRA_CA_CERTS`)は不要。

`docker-compose.yml` の `web` サービスは `./apps/web:/app` をバインドマウントするため、
イメージビルド時に作られた `/app/node_modules` はマウントで覆い隠される。クローン直後など
ホストに `apps/web/node_modules` が無い場合、`apps/web/docker-entrypoint.sh` が起動時に
それを検知してコンテナ内(`node:22-alpine`、musl)で `npm ci` を実行し、生成された
`node_modules` の所有者をバインドマウント元(ホストの実行ユーザー)へ揃える。ホストの
Node(`npm install` 済みの場合)とコンテナのNodeでネイティブバイナリ(`@next/swc`等)の
ABIが異なりうるため、インストールは常にコンテナ内で行う(#1050)。

`docker-entrypoint.sh` は `npm ci`/`chown` のために一旦rootで動くが、最後に
`su-exec`でバインドマウント元(=ホストの実行ユーザー)の uid/gid へ権限を落としてから
`next dev` を実行する(#1042)。これにより、コンテナが稼働し続ける間に `next dev` が
作り続ける `apps/web/.next` や `apps/web/next-env.d.ts` もホストユーザー所有のまま
保たれ、`docker compose up -d` 後にホストから `cd apps/web && npm run build` が
そのまま実行できる。`.next` を匿名/named volumeにせずバインドマウント内に置く方針
(上記コメント参照、ルートディスク圧迫を避けるため)は変えていない。

#1042 以前に起動したことがあり、`apps/web/.next` や `apps/web/next-env.d.ts` が
既にroot所有で残っている場合は、ホストの `sudo rm -rf apps/web/.next` で削除するか
(次回起動時にホストユーザー所有で作り直される)、`docker run --rm -v
"$(pwd)/apps/web:/app" alpine chown -R "$(id -u):$(id -g)" /app/.next
/app/next-env.d.ts` のようにコンテナ経由でsudo無しに所有者を付け替える。

### (代替)ホスト上で `npm run dev` を直接起動する場合

より高速なホットリロードを求める場合など、コンテナを使わずホスト上で直接起動することもできる。
この場合は `docker-compose.yml` の `web` サービスを停止し(`docker compose stop web`)、
リバースプロキシがホスト側の3000番へ到達できるよう `infra/nginx/conf.d/default.conf` の
`location /` の `proxy_pass` 先を `host.docker.internal:3000` に戻す必要がある(Linuxでは
`reverse-proxy` サービスに `extra_hosts: ["host.docker.internal:host-gateway"]` の追加が必要)。

```bash
cd apps/web
cp .env.local.example .env.local
vi .env.local   # LETS_BLOG_GATEWAY_URL=https://localhost, NODE_EXTRA_CA_CERTS=../certs/localhost.crt 等
npm install
npm run dev
```

この方式ではWeb管理画面のサーバーサイドfetchが `https://localhost` 経由になり自己署名証明書を
経由するため、`.env.local` で `NODE_EXTRA_CA_CERTS=../certs/localhost.crt` の指定が必須
(未設定だと `DEPTH_ZERO_SELF_SIGNED_CERT` エラーで失敗する)。

## 6. 初回管理者アカウントの作成

初回アクセス時、まだユーザーが1人も存在しない場合は `/setup` にリダイレクトされ、
セルフサインアップで最初のユーザー(管理者権限)を作成できる。

## 7. VSCode拡張の設定

拡張の設定 `letsBlog.serverUrl`(既定値: `https://localhost`)と、
APIキー(`Let's Blog: Set API Key` コマンドで `SERVER_API_KEY` と同じ値を設定)が必要。

拡張(VSCodeの拡張ホスト、Node.jsで動作)から `https://localhost` へアクセスする際も
自己署名証明書の検証が行われるため、VSCodeを起動するシェルで
`NODE_EXTRA_CA_CERTS=/path/to/certs/localhost.crt` を設定してから `code .` 等で起動するか、
OS/ブラウザの証明書ストアに `certs/localhost.crt` を信頼済み証明書として登録する。

**ダイアグラム機能(draw.io統合)を使う場合は、OS/ブラウザの証明書ストアへの登録が必須。**
`NODE_EXTRA_CA_CERTS` は拡張ホスト(Node.js)からのAPI呼び出しにのみ有効で、
draw.ioエディタ画面はVSCode Webview内のiframeとして`https://localhost/drawio/`を
Chromiumのレンダラープロセスで直接読み込むため、`NODE_EXTRA_CA_CERTS`ではなく
Chromiumが参照する証明書ストアの信頼設定が必要になる(未登録の場合、証明書エラーで
iframeの読み込みがブロックされ、パネルが白紙のまま表示される)。

Linuxの場合、VSCode(Electron/Chromium)は `~/.pki/nssdb` のNSS証明書データベースを
参照する。`libnss3-tools` パッケージの `certutil` で登録する。

```bash
# 初回のみ: NSSデータベースが無ければ作成する
mkdir -p ~/.pki/nssdb && certutil -N -d sql:$HOME/.pki/nssdb --empty-password

# 証明書を信頼済みCAとして登録
certutil -A -d sql:$HOME/.pki/nssdb -t "C,," -n "LetsBlog Local Dev" -i /path/to/certs/localhost.crt
```

登録後、VSCodeを完全に再起動する(ウィンドウの再読み込みだけでは反映されない場合がある)。
macOS/Windowsの場合はキーチェーンアクセス/証明書マネージャーへ登録する(OS標準の証明書ストアを
Chromiumがそのまま参照するため、Linuxのような追加ツールは不要)。

## 8. ローカル環境へのマスタ環境データの同期(開発用)

ローカルで開発する際、空のプレースホルダデータではなく実際に近いコンテンツ(記事・メディア・
テーマ/プラグイン設定)で動作確認したい場合、プロジェクトの「マスタ環境」(テストまたは本番)から
ローカル環境へデータを同期できる(issue #325)。

**前提条件**: 同期元(マスタ環境)・同期先(ローカル)の両方が、このアプリで自動構築(managed)した
WordPress環境である必要がある。SSH接続/REST接続で外部のWordPressホスティングを紐付けている
プロジェクトでは、この方法によるDB・メディアの一括同期は現時点では未対応(将来の拡張予定)。

**手順**:

1. プロジェクト詳細画面の「概要」タブで、マスタ環境(テスト/本番のどちらか)が設定済みであることを確認する。
2. 「設定」タブの「環境同期」パネルを開く。
3. 「マスタ環境(テスト/本番)→ローカルの設定を入力」ボタンをクリックすると、
   同期元にマスタ環境、同期先にローカル、同期対象(テーマ/プラグイン/メディア/DB)が
   すべて選択された状態になる(手動で個別に選び直すことも可能)。
4. 内容を確認し「同期する」をクリックする(確認ダイアログが出るので、同期先の内容が
   上書きされることを理解した上で承諾する)。
5. 同期完了後、ローカル環境のWordPress管理画面・記事一覧などで反映内容を確認できる。

**同期される内容・されない内容**:

- DB同期は `wp_users` / `wp_usermeta` を除外するため、ローカル環境の管理者アカウントは
  上書きされない。
- DB内のサイトURLは、マスタ環境のURLからローカル環境のURLへ自動的に書き換えられる
  (`wp search-replace` 相当の処理)。
- メディア(`wp-content/uploads`)・テーマ・プラグインは、同期先の既存ファイルをバックアップした上で
  マスタ環境の内容で置き換えられる。

**テーブルプレフィックスが同期元・同期先で異なる場合**(issue #1075):

managedサイトのテーブルプレフィックスは`/provision`で作った直後は`wp_`だが、SSH管理サイトからの
`/db-import`(#511)を経由した環境は、取り込み元に合わせてプレフィックスが書き換わっているため、
同期元・同期先でプレフィックスが食い違うことがある。DB同期はテーブル名(識別子)だけでなく、
WordPressのロール定義(`option_name = '{プレフィックス}user_roles'`)もこのキー1件に限り
同期先のプレフィックスへ付け替えるため、通常の操作で復旧する(手動での対処は不要)。

**再同期しないサイトが既に壊れている場合の手動復旧手順**(#1075修正の適用前に同期していた場合):

同期先のロール定義が失われ、全ユーザーが`wp-admin`を開けなくなっている(全ケーパビリティを失う)
症状が出ている場合は、同じ同期元・同期先の組み合わせで環境同期(DB)をもう一度実行するだけで
復旧する。何らかの理由で再同期できない場合は、`phpMyAdmin`(`/phpmyadmin/`)または
`docker exec`経由のwp-cliで、同期先の実際のテーブルプレフィックス(`wp config get table_prefix`)を
確認した上で、次のSQLを同期先のDBに対して直接実行する(`{prefix}`は同期先の実際のプレフィックスに、
`{正しいロール定義}`は同期元(または同種の正常なサイト)の`{prefix}user_roles`の値に読み替える)。

```sql
DELETE FROM `{prefix}options` WHERE option_name = '{prefix}user_roles';
INSERT INTO `{prefix}options` (option_name, option_value, autoload)
  VALUES ('{prefix}user_roles', '{正しいロール定義}', 'yes');
```

## トラブルシューティング

**ポート80/443が使用中で `docker compose up -d` が失敗する**
他のWebサーバー等が既にそのポートを使用していないか確認する(`sudo ss -ltnp | grep -E ':(80|443)'`)。

**`https://localhost/` (Web管理画面) が 502 を返す**
`web` コンテナが起動していない可能性がある。`docker compose ps` で `lbs-web` が `Up` に
なっているか確認し、`docker compose up -d web` で起動する。ホスト上で `npm run dev` を
直接起動する代替方式を使っている場合は、そのプロセスが起動しているか・ポート3000で
LISTENしているかを確認する(環境変数変更後はプロセス再起動が必要)。

**Web管理画面からのAPI呼び出しが `DEPTH_ZERO_SELF_SIGNED_CERT` で失敗する**
コンテナ化された `web` サービスでは内部通信が平文HTTP(`http://api:8080`)のため通常発生しない。
ホスト上で `npm run dev` を直接起動する代替方式を使っている場合のみ、`apps/web/.env.local` の
`NODE_EXTRA_CA_CERTS` が正しいパス(`../certs/localhost.crt`)を指しているか確認し、
`npm run dev` を再起動する(環境変数の変更はプロセス再起動が必要)。

**GPU (NVIDIA) が認識されない**
ホスト側で `nvidia-smi` が動作するか、NVIDIA Container Toolkitが導入済みか確認する。
`docker compose logs comfyui` でGPU認識ログを確認できる。

**下書き/校正/要約・タグ提案・記事プランニングが失敗する(LLM呼び出しエラー)**
`.env` の `LLM_API_KEY` が正しく設定されているか確認する。ダッシュボードの接続サービス状況
(admin限定)で `LLM` がWARNINGの場合はAPIキー未設定、ERRORの場合は`docker compose logs api`で
詳細なエラー内容(レート制限・認証エラー等)を確認する。

**各サービスの個別ポート(内部8080等)に直接アクセスできない**
Phase 6以降は意図した仕様(すべて `https://localhost/...` 経由に一本化。内部ポートは#556で
全サービス8080に統一済み)。デバッグ目的で一時的に直接アクセスしたい場合は、
`docker exec <コンテナ名> curl ...` でコンテナ内から確認するか、該当サービスの
`docker-compose.yml` に一時的に `ports:` を追加する(恒久的な変更はしないこと)。

## 関連ドキュメント

- [infra/nginx/conf.d/default.conf](../infra/nginx/conf.d/default.conf) — リバースプロキシのルーティング設定
  (どのパスをどのサービスへ振り分けるか、その判断理由がコメントに書かれている)
- [docs/DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md) — コンテナ構成・ポート割当・起動順序
- [.env.example](../.env.example) — 環境変数の全項目
