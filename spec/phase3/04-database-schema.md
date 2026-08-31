# 04. データベーススキーマ・エンティティ・DTO の汎用化

## 目的

Phase2までの `sites` テーブルはWordPress固有カラム(`wp_username`, `wp_app_password_encrypted`)のみで、CMS種別を区別する情報がない。本タスクでは、新しいFlywayマイグレーション(`V3`)を追加し、**CMS種別判別カラム** と **汎用認証情報カラム** を追加。あわせてエンティティ・DTO・Service の全レイヤーで CMS非依存の設計に更新する。

既存の WordPressサイト(Phase2で登録されたもの)とのデータ互換性を保つため、既存カラムは削除せず、フォールバックロジックで読み取る設計。

## 前提・決定事項

- Flyway マイグレーションは **破壊的変更を避ける**(CLAUDE.mdの指針に従う)。既存 `wp_username` / `wp_app_password_encrypted` 列は残す。
- 新カラム: `sites.cms_type` (VARCHAR, NOT NULL, DEFAULT 'WORDPRESS') と `sites.credentials_encrypted` (VARBINARY, nullable)。
- `sites.posts` テーブルの `wp_post_id` 列は型を BIGINT → VARCHAR に変更(microCMS は数値以外のIDを使用)。
- `SiteService.getCredentials()` に **フォールバック** を実装: `credentials_encrypted` が null なら `wp_username`/`wp_app_password_encrypted` から `WordPressCredentials` を組み立てる。
- 新規サイト登録は常に `cms_type`+`credentials_encrypted` を使用(汎用化)。

## コンポーネント構成・変更内容

### Flyway マイグレーション `V3__add_cms_type_and_generic_credentials.sql` (新規)

```sql
-- cms_type カラムを追加(既存 WordPress サイトは DEFAULT 'WORDPRESS' で自動設定)
ALTER TABLE sites
    ADD COLUMN cms_type VARCHAR(50) NOT NULL DEFAULT 'WORDPRESS' AFTER site_key;

-- 汎用認証情報カラムを追加
ALTER TABLE sites
    ADD COLUMN credentials_encrypted VARBINARY(2048) NULL AFTER wp_app_password_encrypted;

-- 既存 WordPress 専用カラムを NULL 許容に(新規登録サイトでは使わないため)
ALTER TABLE sites
    MODIFY COLUMN wp_username VARCHAR(255) NULL;

ALTER TABLE sites
    MODIFY COLUMN wp_app_password_encrypted VARBINARY(1024) NULL;

-- posts テーブルの wp_post_id を VARCHAR に拡張(microCMS は数値ID以外)
ALTER TABLE posts
    MODIFY COLUMN wp_post_id VARCHAR(255) NULL;
```

実行順序:
1. cms_type 列追加(既存行は DEFAULT 'WORDPRESS' で自動設定)
2. 汎用認証情報カラム追加
3. WordPress 専用カラムを NULL 許容に変更
4. posts テーブルの ID 列を拡張

Flyway による自動実行時に既存データの整合性が保たれる。

### `Site.java` エンティティ (修正)

```java
package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sites")
@Getter
@Setter
@NoArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "site_key", nullable = false, unique = true, length = 100)
    private String siteKey;

    @Column(name = "cms_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private CmsType cmsType;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    // 既存 WordPress 専用カラム(後方互換用)
    @Column(name = "wp_username", nullable = true)
    private String wpUsername;

    @Column(name = "wp_app_password_encrypted", nullable = true)
    private byte[] wpAppPasswordEncrypted;

    // 新しい汎用認証情報カラム
    @Column(name = "credentials_encrypted", nullable = true)
    private byte[] credentialsEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

重要な点:
- `cmsType` を `@Enumerated(EnumType.STRING)` で管理(文字列形式で DB に格納)
- WordPress 系の既存カラムは nullable に
- `credentialsEncrypted` は新規登録時のみ使用

### `Post.java` エンティティ (修正)

```java
@Column(name = "wp_post_id", nullable = true, length = 255)
private String wpPostId;  // Long → String に変更
```

### `SiteRegisterRequest.java` DTO (修正)

```java
package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SiteRegisterRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotNull CmsType cmsType,
        @NotNull Map<String, String> credentials  // CMS 種別ごとの可変フィールド
) {
}
```

`credentials` Map の要素例:
- WordPress: `{ baseUrl: "...", username: "admin", appPassword: "..." }`
- microCMS: `{ serviceId: "...", apiKey: "...", managementApiKey: "...", postsEndpoint: "...", categoriesEndpoint: "...", tagsEndpoint: "..." }`

### `SiteResponse.java` DTO (修正)

```java
package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;

import java.time.LocalDateTime;

public record SiteResponse(
        Long id,
        String name,
        String siteKey,
        CmsType cmsType,
        String baseUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SiteResponse from(Site site) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getSiteKey(),
                site.getCmsType(),
                site.getBaseUrl(),
                site.getCreatedAt(),
                site.getUpdatedAt()
        );
    }
}
```

重要な点:
- `wpUsername` を削除(秘匿情報は返さない)
- `cmsType` を追加
- `baseUrl` は共通フィールドとして残す

### `SiteService.java` サービス (修正)

```java
package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.repository.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class SiteService {

    private final SiteRepository siteRepository;
    private final CredentialCipher credentialCipher;
    private final ObjectMapper objectMapper;

    public SiteService(SiteRepository siteRepository, CredentialCipher credentialCipher, ObjectMapper objectMapper) {
        this.siteRepository = siteRepository;
        this.credentialCipher = credentialCipher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SiteResponse register(SiteRegisterRequest request) {
        if (siteRepository.existsBySiteKey(request.siteKey())) {
            throw new IllegalArgumentException("siteKey '" + request.siteKey() + "' は既に登録されています");
        }

        validateCredentials(request.cmsType(), request.credentials());

        Site site = new Site();
        site.setName(request.name());
        site.setSiteKey(request.siteKey());
        site.setCmsType(request.cmsType());
        site.setBaseUrl(request.credentials().get("baseUrl"));

        // 新規登録は常に credentials_encrypted に一本化
        String credentialsJson = objectMapper.writeValueAsString(request.credentials());
        site.setCredentialsEncrypted(credentialCipher.encrypt(credentialsJson));

        return SiteResponse.from(siteRepository.save(site));
    }

    private void validateCredentials(CmsType cmsType, Map<String, String> credentials) {
        switch (cmsType) {
            case WORDPRESS:
                if (!credentials.containsKey("baseUrl") || credentials.get("baseUrl").isBlank()) {
                    throw new IllegalArgumentException("WordPress: baseUrl は必須です");
                }
                if (!credentials.containsKey("username") || credentials.get("username").isBlank()) {
                    throw new IllegalArgumentException("WordPress: username は必須です");
                }
                if (!credentials.containsKey("appPassword") || credentials.get("appPassword").isBlank()) {
                    throw new IllegalArgumentException("WordPress: appPassword は必須です");
                }
                break;
            case MICROCMS:
                String[] required = {"serviceId", "apiKey", "managementApiKey", "postsEndpoint", "categoriesEndpoint", "tagsEndpoint"};
                for (String key : required) {
                    if (!credentials.containsKey(key) || credentials.get(key).isBlank()) {
                        throw new IllegalArgumentException("microCMS: " + key + " は必須です");
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("未対応のCMS種別: " + cmsType);
        }
    }

    @Transactional(readOnly = true)
    public List<SiteResponse> list() {
        return siteRepository.findAll().stream().map(SiteResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Site getBySiteKey(String siteKey) {
        return siteRepository.findBySiteKey(siteKey)
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません"));
    }

    @Transactional(readOnly = true)
    public CmsCredentials getCredentials(String siteKey) {
        Site site = getBySiteKey(siteKey);

        // 新規登録サイト: credentials_encrypted から解復
        if (site.getCredentialsEncrypted() != null) {
            String decrypted = credentialCipher.decrypt(site.getCredentialsEncrypted());
            Map<String, String> credentials = objectMapper.readValue(decrypted, Map.class);
            return buildCredentialsFromMap(site.getCmsType(), credentials);
        }

        // 既存 WordPress サイト(後方互換): wp_username/wp_app_password_encrypted から復元
        if (site.getWpUsername() != null && site.getWpAppPasswordEncrypted() != null) {
            String appPassword = credentialCipher.decrypt(site.getWpAppPasswordEncrypted());
            return new CmsCredentials.WordPressCredentials(site.getBaseUrl(), site.getWpUsername(), appPassword);
        }

        throw new IllegalStateException("サイト '" + siteKey + "' の認証情報が無効です");
    }

    private CmsCredentials buildCredentialsFromMap(CmsType cmsType, Map<String, String> credentials) {
        switch (cmsType) {
            case WORDPRESS:
                return new CmsCredentials.WordPressCredentials(
                        credentials.get("baseUrl"),
                        credentials.get("username"),
                        credentials.get("appPassword")
                );
            case MICROCMS:
                return new CmsCredentials.MicroCmsCredentials(
                        credentials.get("serviceId"),
                        credentials.get("apiKey"),
                        credentials.get("managementApiKey"),
                        credentials.get("postsEndpoint"),
                        credentials.get("categoriesEndpoint"),
                        credentials.get("tagsEndpoint")
                );
            default:
                throw new IllegalArgumentException("未対応のCMS種別: " + cmsType);
        }
    }
}
```

重要な点:
- `register()` で新規登録時は credentials Map を JSON化・AES-256-GCM暗号化して `credentials_encrypted` に保存
- `getCredentials()` で読み取り時は、`credentials_encrypted` が非null なら復号→Map→CmsCredentials生成。null なら既存WordPress用のフォールバック実装
- `validateCredentials()` で CMS種別ごとの必須キーをチェック
- ObjectMapper は Spring が自動注入する (`spring-boot-starter-web` に含まれる)

### `SiteController.java` (修正不要だが確認)

既存の `POST /api/sites` エンドポイントの request body が `SiteRegisterRequest` を使っているため、自動で新しいDTO定義に従う。呼び出し側(Web FrontendとAPIクライアント)で request body の構造を変更するだけで OK(06-web-frontend.md 参照)。

## タスクチェックリスト

- [x] `V3__add_cms_type_and_generic_credentials.sql` 作成(Flyway マイグレーション)
- [x] `Site.java`: `cmsType`, `credentialsEncrypted` 追加、既存WordPress カラムを nullable に
- [x] `Post.java`: `wpPostId` 型を `Long` → `String` に
- [x] `SiteRegisterRequest.java`: `cmsType`, `credentials: Map<String,String>` に汎用化
- [x] `SiteResponse.java`: `wpUsername` 削除、`cmsType` 追加
- [x] `SiteService.java`:
  - [x] `register()` で credentials Map を JSON化・暗号化して `credentials_encrypted` に保存
  - [x] `getCredentials()` に フォールバックロジック実装(既存WordPress サイトの互換性維持)
  - [x] `validateCredentials()` 実装(CMS種別ごとの必須キー検証)
  - [x] `buildCredentialsFromMap()` 実装(CMS種別から CmsCredentials インスタンス生成)
- [x] `./gradlew test` でエンティティ/DTO/Service の単体テスト PASS
- [x] `./gradlew bootRun` で起動、Flyway マイグレーション `V3` が正常に実行される ことを確認(ログ: `Migrating schema "lets_blog" to version "3 - add cms type and generic credentials"`)
- [x] MySQL に直接接続して、`sites` テーブルに新カラム `cms_type`, `credentials_encrypted` が追加され、既存WordPressサイトが `cms_type=WORDPRESS` かつ `credentials_encrypted=NULL`(レガシーフォールバック対象)になっていることを確認

## 実装状況

計画通り実装。`resolveDisplayBaseUrl()` を追加し、microCMSサイト登録時は `serviceId` から `https://{serviceId}.microcms.io` を算出して `sites.base_url`(表示用の共通カラム)に保存するようにした。実機検証は一時WordPressコンテナで新規作成・更新・画像アップロード・カテゴリ/タグ自動作成まで一通り実施済み([00-overview](00-overview.md)参照)。

## 未決事項

- credentials Map の Jackson シリアライズ時に、CMS種別に応じた JSON スキーマを固定化するか、あるいは自由形式のまま扱うか。現状は自由形式(Map<String,String>)で OK。型安全性が必要な場合は sealed class に変更する選択肢あり。
- `SiteResponse` に暗号化された credentials の復号後の値を含めるか否か。現状は含めない(秘匿情報は response には含めない)。必要に応じて改修可。
