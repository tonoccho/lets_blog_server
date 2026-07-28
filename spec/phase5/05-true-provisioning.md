# 05. 真のプロビジョニング機能

## 目的

Phase 4で実装したサイト登録機能は、CMS認証情報の検証と保存のみを行い、CMS側へのリソース作成は行わない「可視化のみ」の実装であった。Phase 5では、サイト登録時に実際にCMS側(WordPress/microCMS)に対して、デフォルトカテゴリ・タグ・著者等のリソースを自動作成する機能を実装する。これにより、ユーザーがサイト登録直後から即座に投稿を公開できるようにする。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| プロビジョニング対象CMS | WordPress(カテゴリ・タグ・著者) / microCMS(将来対応、要仕様確認) |
| プロビジョニング対象リソース | デフォルトカテゴリ(Uncategorized相当) / デフォルトタグ / サイト管理者の著者情報 |
| プロビジョニング実行タイミング | サイト登録直後、同期的に実行(トランザクション内) |
| 失敗時の処理 | トランザクション内での実行のため自動ロールバック |
| プロビジョニング再実行 | 手動コマンド(管理画面)で既存サイトに対して再実行可能 |
| 権限チェック | admin ユーザーのみがプロビジョニング機能にアクセス可能 |

## コンポーネント構成

### `ProvisioningService.java` (新規サービス)

```java
package com.letsblog.api.service;

import com.letsblog.api.adapter.CmsAdapter;
import com.letsblog.api.adapter.CmsAdapterFactory;
import com.letsblog.api.domain.Site;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@Slf4j
public class ProvisioningService {

    private final CmsAdapterFactory cmsAdapterFactory;

    public ProvisioningService(CmsAdapterFactory cmsAdapterFactory) {
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * サイト登録時にCMS側へのプロビジョニング(リソース作成)を実行する。
     * トランザクション内での実行のため、失敗時は自動ロールバック。
     */
    @Transactional
    public ProvisioningResult provisionSite(Site site) {
        try {
            CmsAdapter adapter = cmsAdapterFactory.getAdapter(site.getCmsType(), site.getCredentials());

            log.info("Starting provisioning for site: {} ({})", site.getId(), site.getCmsType());

            // CMS種別ごとのプロビジョニング実行
            ProvisioningResult result = new ProvisioningResult();

            // 1. デフォルトカテゴリ作成
            try {
                String defaultCategoryId = adapter.provisionDefaultCategory();
                result.defaultCategoryId = defaultCategoryId;
                log.info("Default category provisioned: {}", defaultCategoryId);
            } catch (Exception e) {
                log.warn("Failed to provision default category: {}", e.getMessage());
                result.categoryError = e.getMessage();
            }

            // 2. デフォルトタグ作成
            try {
                String defaultTagId = adapter.provisionDefaultTag();
                result.defaultTagId = defaultTagId;
                log.info("Default tag provisioned: {}", defaultTagId);
            } catch (Exception e) {
                log.warn("Failed to provision default tag: {}", e.getMessage());
                result.tagError = e.getMessage();
            }

            // 3. サイト管理者を著者として登録
            try {
                String authorId = adapter.provisionAuthor(site.getOwnerEmail(), site.getOwnerName());
                result.authorId = authorId;
                log.info("Author provisioned: {}", authorId);
            } catch (Exception e) {
                log.warn("Failed to provision author: {}", e.getMessage());
                result.authorError = e.getMessage();
            }

            result.success = true;
            return result;
        } catch (Exception e) {
            log.error("Provisioning failed for site {}: {}", site.getId(), e.getMessage(), e);
            throw new ProvisioningException("サイトのプロビジョニングに失敗しました", e);
        }
    }

    /**
     * 既存サイトのプロビジョニングを再実行(管理画面用)
     */
    @Transactional
    public ProvisioningResult reprovisioner(Site site) {
        log.info("Reprovisioning site: {}", site.getId());
        return provisionSite(site);
    }

    public static class ProvisioningResult {
        public boolean success = false;
        public String defaultCategoryId;
        public String categoryError;
        public String defaultTagId;
        public String tagError;
        public String authorId;
        public String authorError;
    }
}
```

### `CmsAdapter.java` (既存インターフェースの拡張)

```java
package com.letsblog.api.adapter;

import java.util.Map;

public interface CmsAdapter {
    // ... 既存メソッド ...

    /**
     * デフォルトカテゴリを作成し、IDを返す。
     * WordPress: Uncategorized に相当するカテゴリを作成
     * microCMS: list型APIのカテゴリ一覧に初期値を作成
     */
    String provisionDefaultCategory() throws CmsException;

    /**
     * デフォルトタグを作成し、IDを返す。
     * WordPress: デフォルトタグを作成
     * microCMS: list型APIのタグ一覧に初期値を作成
     */
    String provisionDefaultTag() throws CmsException;

    /**
     * サイト管理者を著者として登録し、IDを返す。
     * WordPress: ユーザーを新規作成 or 既存著者を取得
     * microCMS: リソース内の作成者フィールドを初期化
     */
    String provisionAuthor(String email, String name) throws CmsException;
}
```

### `WordPressAdapter.java` (プロビジョニング実装)

```java
// WordPressAdapter.java の既存メソッドに以下を追加

@Override
public String provisionDefaultCategory() throws CmsException {
    try {
        String url = baseUrl + "/wp-json/wp/v2/categories";

        // 既に Uncategorized が存在するか確認
        HttpResponse<String> getResponse = client
                .send(createGetRequest(url + "?slug=uncategorized"), HttpResponse.BodyHandlers.ofString());

        if (getResponse.statusCode() == 200) {
            JsonNode categories = objectMapper.readTree(getResponse.body());
            if (categories.isArray() && categories.size() > 0) {
                return categories.get(0).get("id").asText();
            }
        }

        // 存在しない場合は作成
        Map<String, String> categoryData = Map.of(
                "name", "Uncategorized",
                "slug", "uncategorized",
                "description", "Default category"
        );

        String body = objectMapper.writeValueAsString(categoryData);
        HttpRequest request = createPostRequest(url, body);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode result = objectMapper.readTree(response.body());
            return result.get("id").asText();
        } else {
            throw new CmsApiException("Failed to create default category: " + response.statusCode());
        }
    } catch (Exception e) {
        throw new CmsException("プロビジョニング: デフォルトカテゴリ作成に失敗", e);
    }
}

@Override
public String provisionDefaultTag() throws CmsException {
    try {
        String url = baseUrl + "/wp-json/wp/v2/tags";

        Map<String, String> tagData = Map.of(
                "name", "Let's Blog",
                "slug", "letsblog",
                "description", "Default tag for Let's Blog"
        );

        String body = objectMapper.writeValueAsString(tagData);
        HttpRequest request = createPostRequest(url, body);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode result = objectMapper.readTree(response.body());
            return result.get("id").asText();
        } else {
            throw new CmsApiException("Failed to create default tag: " + response.statusCode());
        }
    } catch (Exception e) {
        throw new CmsException("プロビジョニング: デフォルトタグ作成に失敗", e);
    }
}

@Override
public String provisionAuthor(String email, String name) throws CmsException {
    try {
        String url = baseUrl + "/wp-json/wp/v2/users";

        Map<String, String> userData = Map.of(
                "username", email.split("@")[0], // email の @ 前をユーザー名に
                "email", email,
                "name", name,
                "roles", "[\"author\"]"
        );

        String body = objectMapper.writeValueAsString(userData);
        HttpRequest request = createPostRequest(url, body);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode result = objectMapper.readTree(response.body());
            return result.get("id").asText();
        } else if (response.statusCode() == 409) {
            // ユーザー既存の場合(重複)
            log.warn("Author already exists: {}", email);
            // 既存ユーザーのIDを取得
            return retrieveExistingAuthorId(email);
        } else {
            throw new CmsApiException("Failed to provision author: " + response.statusCode());
        }
    } catch (Exception e) {
        throw new CmsException("プロビジョニング: 著者作成に失敗", e);
    }
}

private String retrieveExistingAuthorId(String email) throws Exception {
    String url = baseUrl + "/wp-json/wp/v2/users?search=" + email;
    HttpResponse<String> response = client.send(createGetRequest(url), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() == 200) {
        JsonNode users = objectMapper.readTree(response.body());
        if (users.isArray() && users.size() > 0) {
            return users.get(0).get("id").asText();
        }
    }
    throw new CmsException("既存著者の取得に失敗: " + email);
}
```

### `SiteService.java` (プロビジョニング統合)

```java
// SiteService.java の registerSite メソッドを以下のように修正

@Transactional
public SiteResponse registerSite(SiteRegisterRequest request, User owner) {
    // 既存のサイト登録処理
    Site site = new Site();
    site.setName(request.name());
    site.setUrl(request.url());
    site.setCmsType(request.cmsType());
    site.setCredentials(encryptCredentials(request.credentials()));
    site.setOwnerId(owner.getId());
    site.setOwnerEmail(owner.getEmail());
    site.setOwnerName(owner.getName());

    // 疎通確認
    verifyConnection(site);

    // プロビジョニング実行
    try {
        ProvisioningService.ProvisioningResult provisioningResult = provisioningService.provisionSite(site);
        
        // プロビジョニング結果をログに記録
        if (!provisioningResult.success) {
            log.warn("Provisioning partial failure for site: {}", site.getId());
        }
    } catch (ProvisioningException e) {
        log.error("Provisioning failed, rolling back site registration: {}", e.getMessage());
        throw e; // トランザクション自動ロールバック
    }

    siteRepository.save(site);
    auditLogService.logSiteRegistered(site.getId(), owner.getId());

    return mapToResponse(site);
}
```

### `ProvisioningException.java`

```java
package com.letsblog.api.exception;

public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProvisioningException(String message) {
        super(message);
    }
}
```

### `SiteController.java` にプロビジョニング再実行エンドポイント追加

```java
@PostMapping("/{siteId}/reprovision")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public ResponseEntity<String> reprovisioner(
        @PathVariable Long siteId) {
    Site site = siteRepository.findById(siteId)
            .orElseThrow(() -> new SiteNotFoundException("サイトが見つかりません"));

    try {
        ProvisioningService.ProvisioningResult result = provisioningService.reprovisioner(site);
        return ResponseEntity.ok("プロビジョニングが完了しました。カテゴリID: " + result.defaultCategoryId);
    } catch (ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("プロビジョニングに失敗しました: " + e.getMessage());
    }
}
```

## タスクチェックリスト

- [ ] `ProvisioningService.java` 実装(CMS別プロビジョニング統括)
- [ ] `ProvisioningException.java` 実装
- [ ] `CmsAdapter.java` インターフェースに3つのプロビジョニング メソッド追加
- [ ] `WordPressAdapter.java` にプロビジョニング実装
  - [ ] `provisionDefaultCategory()` 実装
  - [ ] `provisionDefaultTag()` 実装
  - [ ] `provisionAuthor()` 実装
- [ ] `MicroCmsAdapter.java` にプロビジョニング実装(スケルトン、将来の詳細実装は未決事項)
- [ ] `SiteService.registerSite()` にプロビジョニング呼び出しを統合
- [ ] `SiteController` に `/reprovision` エンドポイント追加
- [ ] プロビジョニング失敗時の トランザクション自動ロールバックを確認
- [ ] `ProvisioningServiceTest` 実装
  - [ ] カテゴリ作成のテスト
  - [ ] タグ作成のテスト
  - [ ] 著者作成のテスト
  - [ ] 失敗時ロールバックのテスト
- [ ] `WordPressAdapterProvisioningTest` 実装(Mock HTTP通信)
- [ ] 既存WordPressサイトでのプロビジョニング実機検証
  - [ ] Uncategorized カテゴリの自動作成確認
  - [ ] デフォルトタグ('Let's Blog')の作成確認
  - [ ] サイト管理者の著者作成確認
  - [ ] その後の投稿作成でこれらが利用可能か確認
- [ ] Web フロントエンド: サイト登録後のプロビジョニング結果表示(optional)
- [ ] 管理画面に「プロビジョニングを再実行」ボタン追加(optional)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- プロビジョニング部分失敗時の挙動(カテゴリ作成成功、タグ失敗など)の細かいハンドリング
- 既存ユーザーのprovisioning再実行により、重複リソースが発生した場合の処理(idempotency)
- microCMS側のプロビジョニング仕様(list型API の初期化方法)の確定
- プロビジョニング成功時の通知・ログ(Slack webhook等)
- プロビジョニング情報の永続化(どのリソースがどのサイトで作成されたか)
- 複数CMS拡張時のプロビジョニング戦略の再検討
