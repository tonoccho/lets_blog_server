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
| `gateway` | 8080 | reverse-proxy経由のみ(`/api/`) | #560。JWT検証・レート制限・相関ID・下流ルーティングを一手に引き受けるAPIゲートウェイ。`/actuator/health`で下流(legacy-api・identity)の状態を集約 |
| `identity` | 8080 | gateway経由のみ(`/api/identity/`, `/api/users/`, `/api/roles/`) | #561。ユーザー・ロール管理。現時点ではlegacy-apiと同一の物理スキーマ(lets_blogのusers/roles/role_permissions/user_rolesテーブル)を参照する暫定構成(スキーマ分離自体は#570で対応) |
| `api` | 8080 | gateway経由のみ | `/actuator/health`をヘルスチェックに使用。#560でreverse-proxyからの直接ルーティングをgatewayに置き換えた。ログイン・2FA・APIキー発行は引き続きここが担う(#561でユーザーCRUD/ロール管理のみidentityへ移設) |
| `log-writer` | 8080 | 非公開(RabbitMQコンシューマー) | 旧8081から統一 |
| `mysql` | 3306 | 非公開 | |
| `rabbitmq` | 5672 (+管理UI 15672) | 非公開 | |
| `phpmyadmin` | 80 | reverse-proxy経由のみ(`/phpmyadmin/`) | |
| `keycloak` | 8080(管理/ヘルスチェックは9000) | reverse-proxy経由のみ(`/auth/`) | `KC_HTTP_RELATIVE_PATH=/auth`。#559 |
| `keycloak-postgres` | 5432 | 非公開 | Keycloak専用PostgreSQL。#559 |
| `penpot-frontend` | 8080 | `9001:8080`(直接公開。ハンドオフURL生成のため) | |
| `comfyui` | 8188 | 非公開(api経由) | GPU必須 |
| `plantuml` | 8080 | 非公開(api経由) | |
| `drawio` | 8080 | 非公開(web経由) | |
| `wordpress` | 9000 | 非公開(api経由でプロビジョニング) | |

将来の各ドメインサービス追加時も、内部ポート8080・外部公開はgateway/reverse-proxy経由のみ、
という原則を踏襲する(#560でgatewayを新設済み。各サービス抽出Issueでは
`services/gateway/src/main/resources/application.yml` のルートURIを新サービスへ
向け直すだけで移行できる)。

## サービス起動順序(`depends_on` + healthcheck)

`api` / `log-writer` は `mysql` と `rabbitmq` の `service_healthy` を待ってから起動する
(既存)。今回、`api` / `log-writer` 自身にも `x-actuator-healthcheck` を追加したため、
将来これらに依存する新サービス(gateway等)も `condition: service_healthy` で
正しく待ち合わせできるようになった。

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

## リソース実測

全19コンテナを `docker compose up -d` で起動し、アイドル状態(リクエストなし、
画像生成等の負荷なし)で `docker stats --no-stream` を実測した値。

| コンテナ | メモリ使用量 |
|---|---|
| `lbs-web` | 3002.4 MiB |
| `lbs-penpot-backend` | 1986.6 MiB |
| `lbs-comfyui` | 1371.1 MiB |
| `lbs-api` | 1187.8 MiB |
| `lbs-plantuml` | 568.0 MiB |
| `lbs-penpot-frontend` | 567.3 MiB |
| `lbs-log-writer` | 419.8 MiB |
| `lbs-mysql` | 393.0 MiB |
| `lbs-drawio` | 277.0 MiB |
| `lbs-penpot-exporter` | 177.6 MiB |
| `lbs-rabbitmq` | 141.6 MiB |
| `lbs-penpot-postgres` | 138.8 MiB |
| `lbs-penpot-mcp` | 122.3 MiB |
| `lbs-wordpress` | 65.1 MiB |
| `lbs-penpot-mailcatch` | 43.6 MiB |
| `lbs-phpmyadmin` | 42.6 MiB |
| `lbs-docker-socket-proxy` | 32.5 MiB |
| `lbs-penpot-valkey` | 18.8 MiB |
| `lbs-reverse-proxy` | 17.5 MiB |
| **合計** | **約10.3 GiB** |

**注意点**

- `web` はNext.jsの開発モード(Turbopack, ホットリロード用のbind mount)で実行しているため、
  本番ビルドより大幅にメモリを消費している。本番相当の構成にすればもっと小さくなる見込み。
- `comfyui` はアイドル値。実際に画像生成を行うとGPU VRAM側の使用量が主に増加する
  (ホストメモリへの影響は本測定の範囲外)。
- 上記はアイドル時の実測値であり、ビルド時(`docker compose build`実行中)やAI機能利用時は
  瞬間的にこれを上回る。ホストOS自体の消費分も含め、実測値(約10.3GiB)に対して
  余裕を見て16GB以上を推奨する(README参照)。
- この表は#556時点(19コンテナ)の実測。#559でKeycloak(`keycloak` 約1.4GiB /
  `keycloak-postgres` 約43MiB)が追加され、現在は21コンテナ構成。表全体の再実測は
  今後のリソース監査でまとめて行う。
