# 01. WordPress自動プロビジョニング機能

## 目的

これまでの「サイト登録」は、外部に既に存在するWordPress/microCMSサイトの接続情報を登録するフローのみだった。Phase 7では、Let's Blog Server自身が保持する常駐WordPressコンテナ上に、管理画面の操作だけで新規WordPressインスタンスを自動構築できるようにする。構築完了後は、既存のサイト登録・プロビジョニング機能(Phase4/5)にそのまま接続し、即座に投稿可能な状態にする。

## 前提・決定事項

- WordPress設置方式: 常駐`wordpress`コンテナ1つに対し、サイトごとにサブディレクトリ(`/var/www/html/sites/{slug}/`)へ完全に独立したWordPressコア一式を設置する「サブディレクトリ設置型」(共有ホスティングでの複数WP運用と同じ考え方)。WP Multisiteネットワーク機能は使わない。
- DB: 共有MySQLコンテナに、サイトごとの専用データベース(`wp_{slug}`)を作成。MySQLユーザーは分離せず、既存の共通`lbs_app`ユーザーへ都度`GRANT`する。
- プロビジョニング実行経路: WordPressコンテナ内に**内部限定のプロビジョニングエージェント**を追加する。nginxには公開せず、`lbs-net`内部からのみ到達可能。共有シークレット(`WP_PROVISION_TOKEN`)をヘッダで検証する。Spring Boot APIのDocker socketマウントは行わない(ホストDockerへの過大な権限付与を避けるため)。
- ルーティング: nginxに`/sites/`プレフィックスのlocationを追加。phpMyAdminのような`rewrite`は行わない(Apache DocumentRoot構造とURLパスをそのまま一致させるため、プレフィックスの除去は不要)。
- サイト削除時: WordPress自動構築サイトの削除では、WPインスタンス(サブディレクトリ)と専用DBも連動削除する。外部登録サイトの削除では、このインフラ削除処理は行わない(`sites`テーブルの新規列で判別)。

## アーキテクチャ

```
クライアント(ブラウザ)
    ↓ https://localhost/sites/{slug}/
[nginx reverse-proxy] → [wordpress コンテナ:80(Apache)]
                              └─ DocumentRoot /var/www/html
                                   └─ /sites/{slug}/ (独立したWPコア一式)

Spring Boot API (lbs-net内部のみ)
    ↓ HTTP POST /internal/provision (共有シークレットヘッダ)
[wordpress コンテナ内 provisioning agent] (nginxには非公開)
    ├─ mkdir -p /var/www/html/sites/{slug}
    ├─ mysql -h mysql -u root -p... -e "CREATE DATABASE wp_{slug}; GRANT ALL ON wp_{slug}.* TO 'lbs_app'@'%';"
    ├─ wp core download --path=/var/www/html/sites/{slug}
    ├─ wp config create --path=... --dbname=wp_{slug} --dbuser=lbs_app --dbhost=mysql
    ├─ wp core install --path=... --url=https://localhost/sites/{slug} --title=... --admin_user=... --admin_password=... --admin_email=...
    └─ wp user application-password create --path=... {admin_user} "letsblog" --porcelain
    ↓ (生成したURL・admin_user・アプリケーションパスワードをJSONで返却)
```

## コンポーネント構成

### `wordpress/Dockerfile` (新規)

```dockerfile
FROM wordpress:php8.3-apache

# WP-CLI
RUN curl -O https://raw.githubusercontent.com/wp-cli/builds/gh-pages/phar/wp-cli.phar \
    && chmod +x wp-cli.phar \
    && mv wp-cli.phar /usr/local/bin/wp

# mysql クライアント(DB作成用)
RUN apt-get update && apt-get install -y default-mysql-client && rm -rf /var/lib/apt/lists/*

# プロビジョニングエージェント(PHP内蔵サーバーで内部ポート9000をlisten)
COPY provision-agent /var/www/provision-agent
CMD ["sh", "-c", "php -S 0.0.0.0:9000 -t /var/www/provision-agent & apache2-foreground"]
```

### `wordpress/provision-agent/index.php` (新規、内部限定エージェント)

- `WP_PROVISION_TOKEN`環境変数と、リクエストヘッダ`X-Provision-Token`を比較。不一致なら403。
- `POST /provision`: `{slug, dbName, dbUser, dbPassword, dbHost, title, adminUser, adminEmail, adminPassword}`を受け取り、`mkdir`→`wp core download`→`wp config create`→`wp core install`→`wp user application-password create`を`shell_exec`で順に実行。各コマンドの終了コードを確認し、失敗時は途中経過を含むエラーJSONを返す。成功時は`{url, adminUser, applicationPassword}`を返す。
- `POST /deprovision`: `{slug, dbName}`を受け取り、`rm -rf /var/www/html/sites/{slug}`と`DROP DATABASE`を実行。
- WP-CLIコマンドの引数はシェルインジェクション対策のため`escapeshellarg()`を必ず使う。`slug`はSpring Boot側で英数字・ハイフンのみに正規化してから渡す。

### `docker-compose.yml` (修正)

```yaml
  wordpress:
    build:
      context: ./wordpress
      dockerfile: Dockerfile
    container_name: lbs-wordpress
    restart: unless-stopped
    environment:
      WP_PROVISION_TOKEN: ${WP_PROVISION_TOKEN}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      WORDPRESS_DB_HOST: mysql
    volumes:
      - wordpress_sites:/var/www/html/sites
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - lbs-net
```

`volumes:`に`wordpress_sites:`を追加。

### `.env.example` (追記)

```
# WordPress自動プロビジョニング用の内部共有シークレット(Spring Boot API ↔ wordpress コンテナ間)
# 生成例: openssl rand -hex 32
WP_PROVISION_TOKEN=changeme_wp_provision_token
```

### `nginx/conf.d/default.conf` (追記)

```nginx
    # WordPress自動プロビジョニングサイト(サブディレクトリ設置型、プレフィックス除去は行わない)
    # 正規表現location: 素の /sites, /sites/ はWeb管理画面の「サイト管理」ページ(/sites)と
    # 名前空間が衝突するため、スラッシュ直後にスラッグ(1文字以上)を要求する。
    location ~ ^/sites/([a-z0-9-]+)(/.*)?$ {
        set $upstream_wordpress wordpress:80;
        proxy_pass http://$upstream_wordpress;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        client_max_body_size 100M;
    }
```

`/provision`・`/deprovision`エンドポイント(内部ポート9000)はnginxに一切露出させない(lbs-net内部のコンテナ間通信のみ)。

### `V10__add_site_provisioning_columns.sql` (新規Flywayマイグレーション、custom_tagsのV9の次)

```sql
ALTER TABLE sites
    ADD COLUMN managed_wordpress BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN wp_slug VARCHAR(100),
    ADD COLUMN wp_db_name VARCHAR(100);
```

### `Site.java` (修正)

`managedWordpress` (boolean) / `wpSlug` (String) / `wpDbName` (String) フィールドを追加。

### `WordPressProvisioningClient.java` (新規)

```java
@Component
public class WordPressProvisioningClient {

    private final RestClient client;
    private final String provisionToken;

    public WordPressProvisioningClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.provisionToken = provisionToken;
    }

    public ProvisionResult provision(ProvisionCommand command) {
        try {
            return client.post()
                    .uri("/provision")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .body(ProvisionResult.class);
        } catch (RestClientResponseException e) {
            throw new ProvisioningException("WordPress自動構築に失敗しました: " + e.getStatusCode());
        }
    }

    public void deprovision(String slug, String dbName) {
        try {
            client.post()
                    .uri("/deprovision")
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", slug, "dbName", dbName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ProvisioningException("WordPressインスタンスの削除に失敗しました: " + e.getStatusCode());
        }
    }

    public record ProvisionCommand(String slug, String dbName, String title,
                                    String adminUser, String adminEmail, String adminPassword) {}
    public record ProvisionResult(String url, String adminUser, String applicationPassword) {}
}
```

### `application.yml` (追記)

```yaml
app:
  wordpress-provision-base-url: ${WORDPRESS_PROVISION_BASE_URL:http://localhost:9000}
  wordpress-provision-token: ${WP_PROVISION_TOKEN:}
```

`docker-compose.yml`のapiサービス環境変数に`WORDPRESS_PROVISION_BASE_URL: http://wordpress:9000`と`WP_PROVISION_TOKEN: ${WP_PROVISION_TOKEN}`を追加。

### `WordPressSiteProvisioningService.java` (新規)

```java
@Service
public class WordPressSiteProvisioningService {

    private final WordPressProvisioningClient provisioningClient;
    private final SiteService siteService;
    private final SiteRepository siteRepository;

    /**
     * 新規WordPressインスタンスを常駐コンテナ上に構築し、生成された認証情報で
     * 既存のサイト登録フロー(カテゴリ/タグ/著者プロビジョニングを含む)へ接続する。
     */
    @Transactional
    public SiteResponse createManagedSite(CreateManagedWordPressSiteRequest request, Long actorId) {
        String slug = normalizeSlug(request.siteKey());
        String dbName = "wp_" + slug;

        var result = provisioningClient.provision(new WordPressProvisioningClient.ProvisionCommand(
                slug, dbName, request.title(), request.adminUser(), request.adminEmail(), request.adminPassword()));

        Map<String, String> credentials = Map.of(
                "baseUrl", result.url(),
                "username", result.adminUser(),
                "appPassword", result.applicationPassword());

        SiteRegisterRequest registerRequest = new SiteRegisterRequest(
                request.name(), request.siteKey(), CmsType.WORDPRESS, credentials);
        SiteResponse response = siteService.register(registerRequest, actorId);

        Site site = siteRepository.findBySiteKey(request.siteKey()).orElseThrow();
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        siteRepository.save(site);

        return response;
    }

    @Transactional
    public void deleteManagedSite(Long siteId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));

        if (site.isManagedWordpress()) {
            provisioningClient.deprovision(site.getWpSlug(), site.getWpDbName());
        }
        siteRepository.delete(site);
    }

    private String normalizeSlug(String siteKey) {
        return siteKey.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
```

構築失敗時は`@Transactional`によりDB登録はロールバックされるが、WPコンテナ側に中途半端なディレクトリ・DBが残る可能性がある。この復旧は本フェーズでは自動化せず、手動確認(実機検証時にログを確認)とする。

### `SiteController.java` (修正・追加)

```java
@PostMapping("/managed-wordpress")
public ResponseEntity<SiteResponse> createManagedWordPress(
        @Valid @RequestBody CreateManagedWordPressSiteRequest request) {
    Long actorId = currentActorService.getCurrentActorId();
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(wordPressSiteProvisioningService.createManagedSite(request, actorId));
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@PathVariable Long id) {
    adminAuthorizationService.requireAdmin();
    wordPressSiteProvisioningService.deleteManagedSite(id);
    return ResponseEntity.noContent().build();
}
```

`DELETE /api/sites/{id}`は既存の外部登録サイトにも共通で使う(内部で`managedWordpress`フラグを見て分岐)。

### Web管理画面 (修正)

- `web/src/app/sites/SiteForm.tsx`: 「外部サイトを登録」/「WordPressをこのサーバーに新規構築」の切り替えタブを追加。後者の場合はサイト名・サイトキー・WPサイトタイトル・管理者ユーザー名・メール・パスワードの入力フォームを表示。
- `web/src/app/sites/actions.ts`: `createManagedWordPressSite()` Server Action追加(`POST /api/sites/managed-wordpress`呼び出し)。
- `web/src/app/sites/page.tsx`: 一覧に削除ボタン追加(`DELETE /api/sites/{id}`呼び出し、確認ダイアログ表示)。

## タスクチェックリスト

- [x] `wordpress/Dockerfile` 作成(WP-CLI・mysqlクライアント同梱)
- [x] `wordpress/provision-agent/index.php` 実装(`/provision` / `/deprovision`、共有シークレット検証、シェルインジェクション対策)
- [x] `docker-compose.yml` に `wordpress` サービス追加、`wordpress_sites` ボリューム追加、apiサービスへ環境変数追加
- [x] `.env.example` に `WP_PROVISION_TOKEN` 追加
- [x] `nginx/conf.d/default.conf` に `/sites/` location追加
- [x] `V10__add_site_provisioning_columns.sql` 作成
- [x] `Site.java` にフィールド追加
- [x] `WordPressProvisioningClient.java` 実装
- [x] `CreateManagedWordPressSiteRequest.java` (dto) 実装
- [x] `WordPressSiteProvisioningService.java` 実装
- [x] `SiteController.java` に `/managed-wordpress` POST・`/{id}` DELETE 追加
- [x] `application.yml` に設定項目追加
- [x] `WordPressSiteProvisioningServiceTest.java` 実装(HTTPクライアントをモック)
- [x] Web管理画面: サイト作成フォームの切り替えタブ実装
- [x] Web管理画面: サイト削除UI実装
- [x] `./gradlew test` で全テストPASS確認
- [x] 実機検証: `docker compose up -d --build` → 管理画面からWordPress新規構築 → `https://localhost/sites/{slug}/` でWP管理画面ログイン確認 → 既存投稿パイプラインでの投稿確認 → サイト削除→サブディレクトリ・DB削除確認

## 実装状況(実機検証で判明した計画からの変更点)

実機検証で以下の問題が見つかり、対応した:

1. **credentials.baseUrlとsite.baseUrlの分離**: 計画では両方とも`https://localhost/sites/{slug}`(公開URL)を使う想定だったが、Spring Boot APIコンテナ自身が`https://localhost`を解決すると自分自身(apiコンテナ)を指してしまい疎通できない。`WordPressCredentials`の`baseUrl`(実際のREST API呼び出し先)は`http://wordpress/sites/{slug}`(lbs-net内部の直接到達、Ollama/ComfyUI/PlantUMLと同じ方式)を使い、`sites.base_url`カラム(画面表示・ブラウザ導線用)のみ公開URLで上書きするよう`WordPressSiteProvisioningService`を変更した。
2. **MySQLクライアントのTLS**: `default-mysql-client`(実体はmariadb-client)がデフォルトでTLSハンドシェイクを試み、自己署名証明書エラーで失敗する。`--skip-ssl`を全mysqlコマンドに付与。
3. **wp-cli実行時のメモリ不足**: PHP CLI SAPIのデフォルト`memory_limit`(128M)ではWordPressコア展開時に不足するため、`php -d memory_limit=512M /usr/local/bin/wp`経由で実行するよう変更。
4. **パーマリンク・`.htaccess`未生成問題**: `wp rewrite structure --hard`はApacheモジュールの検出に`apache_get_modules()`を使うが、wp-cliはCLI SAPIで動作するため常に検出失敗し、`.htaccess`の書き込みを警告付きでスキップする(wp-cli既知の制約)。このため`/wp-json/`のようなpretty permalink形式のパスが404になっていた。標準的なWordPress用リライトルールをPHP側で直接`.htaccess`に書き込む処理を追加(サブディレクトリ設置のため`RewriteBase`/遷移先に`/sites/{slug}/`を明示)。
5. **Application Passwords認証がis_ssl()必須**: WordPressのApplication Passwords認証は、そのリクエストが実際にHTTPS経由かどうか(`is_ssl()`)を見て可否を判定する。Spring Boot APIからの内部呼び出しは(2.の理由により)プレーンHTTPのため認証が常に失敗していた。このWordPressインスタンスは常駐コンテナ内でのみ動作し、生のHTTPアクセスが外部に露出することはないため、`wp-config.php`に`$_SERVER['HTTPS'] = 'on';`を注入して`is_ssl()`を常にtrue扱いにする対応とした。
6. **サイト削除時の`posts`テーブルとの外部キー制約**: `posts.site_id`に`fk_posts_site`外部キー制約があり(Phase1から存在)、投稿履歴が残っているサイトを削除すると`DataIntegrityViolationException`が発生していた(サイト削除機能自体が本フェーズの新規実装のため、これまで顕在化していなかった問題)。`WordPressSiteProvisioningService.deleteSite()`で`PostRepository.deleteBySiteId()`を先に実行してから`sites`を削除するよう修正。
7. **reverse-proxyの設定反映**: nginxの`conf.d`はbind mountのため、`nginx/conf.d/default.conf`を編集しただけでは稼働中のreverse-proxyコンテナに反映されない(`nginx -s reload`のタイミングによってはconf.dの読み込みに失敗し、全リクエストが503/接続不可になる不具合を実機検証中に誘発した)。設定ファイル変更後は`docker compose up -d --force-recreate reverse-proxy`でコンテナ自体を再作成する必要がある。
8. **`/sites` 名前空間の衝突(リリース後に発覚)**: Web管理画面には元々サイト管理ページ`/sites`(トレイリングスラッシュなし)が存在する。nginxは`/ollama`等の他サービスと同様に`/sites`→`/sites/`へ301リダイレクトするが、そのリダイレクト先の素の`/sites/`(スラッグなし)がWordPressコンテナ側の空ディレクトリにヒットし、Apacheが403 Forbiddenを返す不具合が発生した(結果として管理画面のサイト管理ページ自体が閲覧不能になっていた)。`location /sites/`(プレフィックスマッチ)を`location ~ ^/sites/([a-z0-9-]+)(/.*)?$`(正規表現、スラッグ必須)に変更し、素の`/sites`・`/sites/`はWeb管理画面へフォールスルーするよう修正した。

## 未決事項

- 構築失敗時の中間生成物(部分的なディレクトリ・DB)の自動クリーンアップ
- WordPressコンテナのリソース上限(サイト数増加時のPHPプロセス・メモリ)
- プロビジョニングエージェントの認証強化(現状は共有シークレットのみ)
- 管理者パスワードの入力方式(管理画面フォームでの直接入力 vs 自動生成して表示)
