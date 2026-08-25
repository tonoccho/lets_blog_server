# 非同期イベント基盤(letsblog.events)

issue #580([C11] 非同期イベント基盤 letsblog.events を整備する)で新設した、ドメインイベント用の
RabbitMQ topic exchange。[ADR-0004](adr/0004-schema-per-service.md)がサービス跨ぎのJOIN・
クロススキーマFKを禁じているため、サービス間の状態伝播は同期API呼び出し(内部ブリッジ)か、この
イベント基盤のいずれかで行う。既存の`letsblog.logs`(issue #466、ログ専用)と設計は共通だが、
関心を分離するため別exchangeとして新設した。

## 前提: このExchangeが解決する問題

`project_ai_settings`(ai-service)・`analytics_credentials`(analytics-service)・
`project_content_settings`(content-service)は、いずれも`projects`(legacy-api所有、project-service未抽出)
の`project_id`を参照キーとして持つが、ADR-0004によりクロススキーマFKを持てない。各サービスへの
スキーマ抽出前は同一スキーマ内FKで`ON DELETE CASCADE`されていたが、抽出後はこの連動が切れており、
プロジェクト削除時にこれらのテーブルへ孤立行が残る実装ギャップがあった(`ProjectService.deleteProject`の
旧コメントは「DB側のCASCADEで消える」と書かれたままになっていた)。`project.deleted`イベントの
購読が、このギャップを埋める。

## Exchange/DLXトポロジー

| 名前 | 種別 | durable | 宣言責任 |
|---|---|---|---|
| `letsblog.events` | topic exchange | ○ | このexchangeに関わる全サービス(プロデューサー・コンシューマー問わず)が各自のRabbitMqConfigで宣言する(`letsblog.logs`と同じ方針。RabbitMQは同一パラメータでの再宣言を許容する) |
| `letsblog.events.dlx` | topic exchange(dead-letter exchange) | ○ | 何らかのキューを持つコンシューマーサービスが宣言する |

**キュー宣言方針**: 「誰が読むか」がキュー・バインディング・DLQの宣言責任を持つ(`letsblog.logs`を
log-writerが宣言するのと同じ方針)。プロデューサー側はexchangeの宣言のみを行い、キューには関与しない。
複数サービスが同じルーティングキーを購読する場合(例: `project.deleted`)、各サービスは
`<サービス名>.<イベント名>.queue`という自分専用のキュー名で個別に宣言する(topic exchangeの
fan-outにより、同じメッセージのコピーがそれぞれのキューに配送される)。

## イベント一覧と実配線状況

初版(issue #580)の6ルーティングキー。`publishing-service`/`project-service`はこのPR時点でまだ
物理的に抽出されておらず(project-serviceはissue #577のPRがまだdevelopにマージされていない、
publishing-serviceはissue #575未着手)、該当ドメインロジックは引き続き`legacy-api`にある。そのため
「発行元」欄が`publishing`/`project`となっているイベントは、実際には`legacy-api`がその役を代行して
発行する。

| ルーティングキー | 発行元(代行込み) | 購読者 | 実配線状況 |
|---|---|---|---|
| `post.published` | legacy-api(`PostPublishService#publish`、publishing-service代行) | content-service | 発行: 実装済み。購読: content-serviceが冪等な受信記録のみ(下記「実配線 vs インフラのみ」参照) |
| `post.deleted` | legacy-api(`PostDeleteService#delete`、publishing-service代行) | content-service, media-service | 発行: 実装済み。購読: content-serviceが冪等な受信記録のみ。media-serviceは未購読(下記参照) |
| `image.generated` | media-service(`GeneratedImageController#create`) | content-service | 発行・購読とも実装済み(受信記録のみ、下記参照) |
| `project.deleted` | legacy-api(`ProjectService#deleteProject`、project-service代行) | content-service, media-service, ai-service, analytics-service | 発行: 実装済み。購読: content-service(`project_content_settings`削除)/ai-service(`project_ai_settings`削除)/analytics-service(`analytics_credentials`削除)は実データ削除。media-serviceは未購読(下記参照) |
| `site.deleted` | legacy-api(`WordPressSiteProvisioningService#deleteSite`、project-service代行) | publishing, content-service | 発行: 実装済み。購読: content-serviceが`posts`を`site_id`で削除(既存の同期内部ブリッジ`ContentServiceClient#deletePostsBySite`と並行、どちらも`deleteBySiteId`に帰着するため冪等) |
| `user.deactivated` | identity-service(`UserService#deactivate`) | 全サービス | 発行: 実装済み。購読: content-serviceが冪等な受信記録のみ(下記参照) |

### 実配線 vs インフラのみ、の内訳

- **実データ削除まで配線**: `project.deleted`(content/ai/analytics-serviceの3サービス)、
  `site.deleted`(content-service)。いずれもクロススキーマFK禁止で生じた実装ギャップを埋める、
  具体的な業務アクションがある。
- **受信記録のみ(処理済みevent_id記録+ログ)**: `post.published`/`post.deleted`/`image.generated`/
  `user.deactivated`のcontent-service側購読。これらはcontent-service側に具体的な業務アクションが
  まだ定義されていない(例: `user.deactivated`は「権限キャッシュの破棄」が用途だが、本PR時点では
  どのサービスも権限キャッシュを持っていないため、実際に破棄する対象がない)。冪等性の仕組み
  (`ProcessedEventStore`/`IdempotentEventHandler`)自体は本物として動作するため、将来サービスが
  実アクションを追加する際は`EventMessageListener`の該当メソッド内にビジネスロジックを足すだけでよい。
- **未購読(インフラ未整備)**: `post.deleted`のmedia-service購読、`project.deleted`の
  media-service購読(`project_image_settings`)。media-serviceの`project_image_settings`テーブルは
  issue #573 stage1でスキーマだけ作られ、Java側の所有権(entity/repository)はまだlegacy-apiに残って
  いる(stage2で移す予定だったが未実施、`V1__create_media_tables.sql`のコメント参照)。そのため
  media-serviceが今`project.deleted`を購読しても削除すべき実データを持たない。これは本Issueのスコープ
  外の別ギャップとして次のIssueで扱う想定(下記「既知の関連ギャップ」参照)。

### 既知の関連ギャップ(本PRのスコープ外)

- `services/project`(project-service、issue #577)はこのPR作成時点でdevelopにマージされていない
  (PR #638がopenのまま)。マージ後は、`project.deleted`/`site.deleted`の発行元をlegacy-apiから
  project-serviceへ移し、legacy-api側の`DomainEventPublisher#publishProjectDeleted`/
  `#publishSiteDeleted`呼び出しを削除する追従が必要。
- media-serviceの`project_image_settings`のJava側所有権移管(issue #573 stage2相当)が完了したら、
  media-serviceに`project.deleted`購読を追加する。
- `post.deleted`のmedia-service購読(「投稿削除に伴う参照整理」)は、`generated_images`テーブルに
  `post_id`列がなく現状紐付けがないため、参照整理すべき対象がない。将来`generated_images`が
  投稿と紐付くようになった時点で購読を追加する。

## イベントの命名規約とペイロード規約

ルーティングキーは`<リソース>.<過去分詞>`(例: `project.deleted`)。`lbs-common`
(`com.letsblog.common.messaging`パッケージ)に、exchange名/DLX名/ルーティングキー定数
(`EventExchanges`)と、イベントごとのペイロード型(record)を定義し、全サービスが同じ型を参照する。

全イベント型は`DomainEvent`インターフェースを実装し、共通で以下の2フィールドを持つ。

```java
public interface DomainEvent {
    String eventId();      // 冪等性キー。プロデューサーが発行のたびに新規採番するUUID文字列
    Instant occurredAt();  // イベント発生時刻
}
```

例(`ProjectDeletedEvent`):

```java
public record ProjectDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long projectId
) implements DomainEvent, Serializable {
}
```

メッセージのシリアライズはJSON(`JacksonJsonMessageConverter`、型解決は送信側の`__TypeId__`
ヘッダーに依存せず`@RabbitListener`引数型からの推論に統一。`letsblog.logs`と同じ方針)。

## 冪等性(重複配信対策)

RabbitMQはat-least-once配送のため、同一イベントが複数回コンシューマーに届きうる
(リトライ、コンシューマー再起動、ack前のクラッシュ等)。`lbs-common`の
`ProcessedEventStore`インターフェース+`IdempotentEventHandler`ヘルパーで、
「同一`eventId`の再配信ではビジネスロジックを実行しない」ことを保証する。

```java
@RabbitListener(queues = "content.project-deleted.queue", containerFactory = "eventsListenerContainerFactory")
@Transactional
public void onProjectDeleted(ProjectDeletedEvent event) {
    IdempotentEventHandler.handle(processedEventStore, event, "project.deleted", () -> {
        projectContentSettingsRepository.deleteByProjectId(event.projectId());
    });
}
```

各コンシューマーサービスは自スキーマに`processed_events`テーブル(`event_id`がPK/UNIQUE制約)を持ち、
`ProcessedEventStore`をJPAで実装する(`existsById`→未処理ならINSERT。即時flushはせず、ビジネス
ロジックと同じトランザクション内でまとめてコミット時にflushさせることで、稀な競合が起きても
トランザクション全体がロールバックされ、再配信時に正しくやり直せる設計。詳細は
`ProcessedEventStore`/`ProcessedEventStoreImpl`のJavadoc参照)。

**検証**: `libs/lbs-common`の`IdempotentEventHandlerTest`(フェイクストアでの単体検証)に加え、
content-service/ai-service/analytics-serviceそれぞれの`EventMessageListenerTest`で、
同一イベントをリスナーメソッドへ複数回直接投入し(RabbitMQの再配送を模す)、業務リポジトリの
呼び出しが1回に収束することをMockitoで確認している。

## 配送失敗時の扱い(DLQ・リトライ)

各コンシューマーキューは、RabbitMQ標準のdead-letter-exchangeパターンで宣言する。

```java
QueueBuilder.durable("content.project-deleted.queue")
        .withArgument("x-dead-letter-exchange", "letsblog.events.dlx")
        .withArgument("x-dead-letter-routing-key", "content.project-deleted.queue.dlq")
        .build();
```

対応するDLQ(`<キュー名>.dlq`)は`letsblog.events.dlx`に同名のルーティングキーでバインドする。

**リトライ回数**: `spring.rabbitmq.listener.simple.retry`(Spring Boot標準機能)で、各コンシューマー
サービスのapplication.ymlに設定する。

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3        # 初回+リトライ2回 = 計3回試行
          initial-interval: 1000 # 1秒→2秒→4秒のバックオフ
          multiplier: 2.0
          max-interval: 10000
        default-requeue-rejected: false  # リトライ上限超過後はrequeueしない
```

`@RabbitListener`メソッドが例外を投げた場合(意図的に握りつぶさない、`letsblog.logs`の
`LogMessageListener`と同じ方針)、上記ポリシーで3回まで再試行し、それでも失敗したメッセージは
`RejectAndDontRequeueRecoverer`(既定のリカバラー)によりrequeueされず、キューの
`x-dead-letter-exchange`設定により`letsblog.events.dlx`経由でDLQへ配送される。DLQに溜まった
メッセージは元のヘッダー(`x-death`)に失敗理由・元のルーティングキー・失敗回数が記録される。

**手動再処理**: DLQのメッセージを元のキューへ戻すには、RabbitMQ管理UIの「Queues → 対象のDLQ →
Get messages」で内容を確認したうえで、以下のいずれかの方法で再投入する。

1. **RabbitMQ管理UI/API経由の手動publish**(推奨、追加ツール不要): 管理UIの「Queues → DLQ →
   Get messages」でペイロードを取得し、「Exchanges → letsblog.events → Publish message」で
   元のルーティングキー(`x-death`ヘッダーで確認できる)を指定して再publishする。少数件の
   手動再処理向け。
2. **Shovelプラグイン**(大量再処理向け): `rabbitmq-plugins enable rabbitmq_shovel
   rabbitmq_shovel_management`でShovelを有効化し、DLQから元のexchange
   (`letsblog.events`、元のルーティングキーで再publish)へメッセージを転送するshovelを一時的に
   構成する。処理が終わったらshovelを削除する。
3. **管理API(`PUT /api/queues/.../contents`は削除専用のため、実際にはconsumeしてpublishし直す
   小さなスクリプトが必要)**: 恒久的な自動再処理が必要になった場合は、DLQをconsumeして
   `letsblog.events`へ再publishするワーカーを追加する(本PR時点では未実装、必要になったら
   別Issueで追加する)。

## RabbitMQ管理UIでの監視

`docker-compose.yml`の`rabbitmq`サービスは`rabbitmq:3-management-alpine`イメージ(管理UI/APIは
コンテナ内部の15672番ポート)。外部公開はしていない(reverse-proxy経由の公開方針、
`docs/DOCKER_COMPOSE_ARCHITECTURE.md`参照)ため、開発環境からは以下のいずれかの方法でアクセスする。

```bash
# 方法1: ポートフォワード(一時的にホストの15672へ転送)
docker run --rm --network lets_blog_server_lbs-net -p 15672:15672 --name rabbitmq-ui-tunnel alpine/socat \
  tcp-listen:15672,fork,reuseaddr tcp-connect:lbs-rabbitmq:15672
# → ブラウザで http://localhost:15672 (ユーザー名/パスワードは.envのRABBITMQ_USER/RABBITMQ_PASSWORD)

# 方法2: コンテナ内から管理APIをcurl(キュー一覧・滞留メッセージ数の確認)
docker exec lbs-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged

# 方法3: 特定exchangeのバインディング確認
docker exec lbs-rabbitmq rabbitmqctl list_bindings source_name routing_key destination
```

キューの滞留(`messages_ready`が増え続ける)はコンシューマーの処理遅延・停止を示すため、
DLQの`messages`件数とあわせて監視する対象。Prometheus等への継続的な監視連携は本PRのスコープ外
(`rabbitmq:3-management-alpine`イメージ自体は`/metrics`エンドポイントでPrometheus形式の
メトリクスを既に公開しているため、将来の監視基盤整備時に追加のコード変更なくスクレイプ対象にできる)。

## 新しいイベントを追加する手順

1. `lbs-common`の`EventExchanges`にルーティングキー定数を追加する。
2. `lbs-common`にペイロードrecord(`DomainEvent`実装)を追加する。
3. 発行元サービスに`DomainEventPublisher`(なければ新設)経由の`rabbitTemplate.convertAndSend`
   呼び出しを追加する。
4. 購読先サービスに、自分専用のキュー名(`<サービス名>.<イベント名>.queue`)でキュー/バインディング/
   DLQをRabbitMqConfigに追加し、`EventMessageListener`に`@RabbitListener`メソッドを追加する。
   ビジネスロジックは必ず`IdempotentEventHandler.handle(...)`経由で実行する。
5. このドキュメントのイベント一覧表を更新する。

## 関連ドキュメント

- [ADR-0004: サービス別スキーマ分離](adr/0004-schema-per-service.md)
- [サービス別スキーマ分離とデータ移行ガイド](SERVICE_SCHEMA_MIGRATION.md)
  (「サービス跨ぎのJOIN/FKの禁止」節でこのイベント基盤への言及あり)
- `letsblog.logs`(ログ専用exchange、issue #466)の実装は
  `libs/lbs-common/src/main/java/com/letsblog/common/messaging/LogExchanges.java`と
  `services/log-writer/src/main/java/com/letsblog/logwriter/config/RabbitMqConfig.java`を参照
  (本ドキュメントが踏襲した設計の元)
