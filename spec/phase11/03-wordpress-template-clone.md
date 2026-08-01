# 03. WordPress新規構築時のテンプレートサイト選択・クローン

## 目的

WordPressを新規構築する際、既存のmanaged WordPressサイトを「テンプレート」として選択できるようにする。選択した場合、通常の空のWordPressコアインストールに加えて、テンプレートサイトのテーマ・プラグイン・メディア・投稿等のコンテンツ(DB)を新規サイトへ複製する。毎回同じテーマ・プラグイン構成・雛形記事から始めたいケース(量産型サイト運用等)を想定する。

## 現状確認

- WordPress新規構築は`WordPressSiteProvisioningService.createManagedSite()`が担っており、`WordPressProvisioningClient.provision()`(`/provision`ハンドラ)で空のWordPressコア+DBを作成した後、`SiteService.register()`でカテゴリ・タグ・著者の初期プロビジョニングを行っている([api/src/main/java/com/letsblog/api/service/WordPressSiteProvisioningService.java](../../api/src/main/java/com/letsblog/api/service/WordPressSiteProvisioningService.java))
- Phase10-03で実装済みの`WordPressSyncClient.sync()`(`/sync`ハンドラ)は、2つのmanagedサイト間でテーマ・プラグイン・(本タスク02完了後は)メディア・DBを無条件上書きコピーする機能を既に持っている。DB同期は`wp_users`/`wp_usermeta`を対象から除外しているため、コピー先の管理者アカウントを上書きしない(`wordpress/provision-agent/index.php`の`/sync`ハンドラ参照)
- この「`wp_users`/`wp_usermeta`を保持したままDB同期する」という既存の除外仕様は、まさに「新規サイトの管理者アカウントは維持したまま、コンテンツだけテンプレートから複製する」というテンプレートクローンの要件と一致する。そのため、新規構築フローの末尾で既存の`WordPressSyncClient.sync()`をテンプレート→新規サイトの向きで1回呼び出すだけで実現できる
- テンプレートとして選べるのは、ファイルシステム・DBへの直接アクセス手段がある`managedWordpress = true`のサイトのみ(外部登録サイトはテンプレートにできない)

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| テンプレートに指定できるサイト | `managedWordpress = true`のサイトのみ(選択UIの候補も同条件でフィルタする) |
| 複製内容 | テーマ・プラグイン・メディア(uploads)・DB(コンテンツ)。DBは既存の`/sync`と同じく`wp_users`/`wp_usermeta`を除外するため、新規サイト作成時に発行した管理者アカウントはそのまま有効 |
| 実現方式 | 新規サイト作成(`/provision`)完了・`SiteService.register()`完了後に、既存の`WordPressSyncClient.sync()`をテンプレートサイト→新規サイトの向きで`targets = ["themes", "plugins", "media", "db"]`固定で1回呼び出す。新規のprovision-agentハンドラは追加しない |
| 失敗時の扱い | クローンに失敗した場合、既存の「登録失敗時は構築済みリソースを削除する」という方針([WordPressSiteProvisioningService.java](../../api/src/main/java/com/letsblog/api/service/WordPressSiteProvisioningService.java)の`deprovision`呼び出し)に倣い、新規作成したWordPressインスタンス・DB・サイトレコードを削除してロールバックする(テンプレート指定時のみクローン失敗がロールバック対象になる。テンプレート未指定の通常構築は従来通り) |
| デフォルトカテゴリ・タグ・著者の初期プロビジョニング | 従来通り`SiteService.register()`内で実行する(クローン前に実行される)。クローンのDB同期でテンプレート側の投稿・カテゴリ・タグに上書きされるため、実質的にはテンプレート側の内容が最終状態になるが、処理順序自体は変更しない(register()の責務を変えないため) |
| テンプレート未選択時の挙動 | 従来通り(空のWordPressコアインストールのみ)。後方互換のため必須入力にはしない |
| URL書き換え | 既存の`/sync`ハンドラが`wp search-replace`で自動的にコピー先自身のURLへ補正するため、追加対応不要 |

## アーキテクチャ・実装詳細

### 全体フロー

```
WordPress新規構築フォーム(ManagedWordPressForm.tsx)
  テンプレートサイト選択(任意、managedWordpressなサイトの一覧から選択)
  ↓ Server Action: createManagedWordPressSiteAction
apiClient.ts: createManagedWordPressSite(input) // input.templateSiteId を追加
  ↓ HTTP POST /api/sites/managed-wordpress
SiteController.createManagedWordPress(request)
  ↓
WordPressSiteProvisioningService.createManagedSite(request, actorId)
  1. provisioningClient.provision(...) // 従来通り、空のWordPressコア+DB作成
  2. siteService.register(...)         // 従来通り、カテゴリ/タグ/著者プロビジョニング
  3. site.setManagedWordpress(true) 等の従来の後処理
  4. request.templateSiteId() が指定されていれば:
       templateSite = siteRepository.findById(templateSiteId)
       templateSiteがmanagedWordpressでなければ IllegalArgumentException
       syncClient.sync(new SyncCommand(
           templateSite.getWpSlug(), templateSite.getWpDbName(),
           slug, dbName, List.of("themes", "plugins", "media", "db")))
       失敗時: provisioningClient.deprovision(slug, dbName) → siteRepository.delete(site) → 例外を再送出
  ↓
レスポンス(SiteResponse)
```

### バックエンド(Spring Boot)

`CreateManagedWordPressSiteRequest`に任意フィールドを追加:

```java
public record CreateManagedWordPressSiteRequest(
        @NotBlank String name,
        @NotBlank String siteKey,
        @NotBlank String title,
        @NotBlank String adminUser,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminPassword,
        String locale,
        Long templateSiteId
) {
}
```

`WordPressSiteProvisioningService.createManagedSite()`に、既存の後処理(`site.setManagedWordpress(true)`等の`siteRepository.save(site)`)の後段としてクローン処理を追加する。`WordPressSyncClient`を新規にコンストラクタ注入する。

```java
if (request.templateSiteId() != null) {
    Site templateSite = siteRepository.findById(request.templateSiteId())
            .orElseThrow(() -> new SiteNotFoundException(
                    "id " + request.templateSiteId() + " のテンプレートサイトは登録されていません"));
    if (!templateSite.isManagedWordpress()) {
        provisioningClient.deprovision(slug, dbName);
        siteRepository.delete(site);
        throw new IllegalArgumentException("テンプレートには自動構築サイトのみ指定できます");
    }
    try {
        syncClient.sync(new WordPressSyncClient.SyncCommand(
                templateSite.getWpSlug(), templateSite.getWpDbName(),
                slug, dbName, List.of("themes", "plugins", "media", "db")));
    } catch (RuntimeException e) {
        provisioningClient.deprovision(slug, dbName);
        siteRepository.delete(site);
        throw e;
    }
}
```

(`media`ターゲットは[02-environment-media-sync.md](02-environment-media-sync.md)の実装完了が前提。02より先に本タスクへ着手する場合は`targets`を`["themes", "plugins", "db"]`とし、02完了後に`"media"`を追記する)

### フロントエンド

`web/src/app/sites/ManagedWordPressForm.tsx`:

- propsに`templateCandidates: Site[]`(managedWordpressなサイトの一覧、`sites/page.tsx`側で`listSites()`の結果をフィルタして渡す)を追加
- フォームに「テンプレートサイト(任意)」の`<select>`を追加(既定「なし」、候補は`templateCandidates`)
- 送信時に選択値を`templateSiteId`としてactionへ渡す

`web/src/app/sites/actions.ts`の`createManagedWordPressSiteAction`: フォームの`managedTemplateSiteId`を読み取り、空でなければ`Number(...)`に変換して`createManagedWordPressSite`の入力へ含める。

`web/src/lib/apiClient.ts`:

```ts
export interface ManagedWordPressSiteInput {
  name: string;
  siteKey: string;
  title: string;
  adminUser: string;
  adminEmail: string;
  adminPassword: string;
  locale: string;
  templateSiteId?: number;
}
```

`web/src/app/sites/SiteCreationPanel.tsx`: `sites`をpropsで受け取り`ManagedWordPressForm`へ`templateCandidates={sites.filter((s) => s.managedWordpress)}`として渡す(現状`users`のみ受け取っているため、`sites/page.tsx`側の呼び出しにも`sites`の受け渡しを追加する)。

構築中の表示文言(`ManagedWordPressForm.tsx`の`"構築中(数分かかる場合があります)…"`)は変更不要だが、テンプレート指定時はDB/メディア量に応じてさらに時間がかかる可能性がある旨をヘルプテキストに追記する。

## スコープ・実装項目

実装対象:

- [ ] `api/src/main/java/com/letsblog/api/dto/CreateManagedWordPressSiteRequest.java`: `templateSiteId`追加
- [ ] `api/src/main/java/com/letsblog/api/service/WordPressSiteProvisioningService.java`: テンプレート指定時のクローン処理・失敗時ロールバック
- [ ] `web/src/lib/apiClient.ts`: `ManagedWordPressSiteInput.templateSiteId`追加
- [ ] `web/src/app/sites/ManagedWordPressForm.tsx`: テンプレート選択UI追加
- [ ] `web/src/app/sites/SiteCreationPanel.tsx`・`web/src/app/sites/page.tsx`: `sites`の受け渡し追加
- [ ] `web/src/app/sites/actions.ts`: `createManagedWordPressSiteAction`でtemplateSiteIdを読み取り

対象外・スコープ外:

- 外部登録(非managed)サイトをテンプレートにすること
- テンプレートの複数階層化(テンプレートのテンプレート)
- 複製内容の個別選択(常にthemes/plugins/media/dbすべてを複製する。個別チェックボックスは設けない)

## 実装順序

1. [02-environment-media-sync.md](02-environment-media-sync.md)完了(前提)
2. `CreateManagedWordPressSiteRequest`・`WordPressSiteProvisioningService`
3. フロントエンド(`apiClient.ts` → `ManagedWordPressForm.tsx` → `SiteCreationPanel.tsx`/`page.tsx` → `actions.ts`)
4. テスト整備・実機検証

## テスト整備

- `WordPressSiteProvisioningServiceTest`(新規、または既存テストクラスへ追加): テンプレート未指定時は従来通り`syncClient.sync`が呼ばれないこと、テンプレート指定時は正しい`slug`/`dbName`/`targets`で`syncClient.sync`が呼ばれること、テンプレートが非managedの場合は例外かつ`deprovision`が呼ばれること、クローン失敗時に`deprovision`と`siteRepository.delete`が呼ばれること

## 実機検証

1. テーマ・プラグインを追加し記事を数件作成済みのmanagedサイトをテンプレートとして、新規WordPress構築を実行
2. 構築完了後、新規サイトのテーマ・プラグイン・記事・添付画像がテンプレートと一致していることを確認
3. 新規サイト作成時に発行した管理者アカウントで新規サイトへログインできること(テンプレート側の管理者アカウントに上書きされていないこと)を確認
4. 新規サイトのURL(`https://localhost/sites/{新規のsiteKey}/`)が正しく機能する(テンプレートのURLに書き換わっていない)ことを確認
5. 存在しない/非managedのサイトIDを`templateSiteId`に指定した場合にエラーとなり、構築済みリソースが残らない(ロールバックされる)ことを確認

## 未決事項・将来検討

- テンプレートサイト自体が更新された場合の再クローン(差分反映)フロー
- 複製対象(themes/plugins/media/db)の個別選択UIの要否
