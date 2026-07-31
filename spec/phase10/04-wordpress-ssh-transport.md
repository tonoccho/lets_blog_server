# 04. WordPress SSH(wp-cli)トランスポートの追加

## 目的

外部登録(非managed)のWordPressサイトの中には、Cloudflare等のHTTPレベルのボット対策によって`wp-json`REST APIへのアクセスがブロックされ、現行のREST APIベースの`WordPressAdapter`では登録・投稿・疎通確認そのものができないものがある。これに対応するため、SSH経由で対象サーバーへ接続し`wp-cli`を実行することで、REST APIを一切使わずに同等の操作を行える新しいトランスポート(`transport = "SSH"`)を`WordPressAdapter`に追加する。

既存のPhase10-03(環境間同期)の未決事項にある「外部登録サイトへの同期対応(SSH鍵配布)」とは別件である。03はmanaged環境同士のテーマ/プラグイン/DB同期の話であり、本ドキュメントは投稿作成・メディアアップロード・カテゴリ/タグ解決・著者作成・疎通確認という`CmsAdapter`の通常操作を、REST不通の外部サイトに対して行うための代替経路の追加である。ただし`ssh/`パッケージ・鍵生成基盤は共通化できるため、将来03の外部サイト対応を実装する際に再利用できる可能性がある。

## 現状確認(実装済み・未着手の棚卸し)

前セッションまでに以下が実装済み(コンパイル・既存テストは通過、ただし本機能単体のテストは未整備):

- `CmsCredentials.WordPressCredentials`にSSH用フィールドを追加済み(`transport`/`sshHost`/`sshPort`/`sshUser`/`wpPath`/`sshPrivateKeyPem`/`sshHostKeyFingerprint`)、`isSsh()`ヘルパー。レガシー3引数コンストラクタは`transport="REST"`固定で残置(後方互換)
- `cms/ssh/`パッケージ: `SshCommandExecutor`(IF、`exec`/`putFile`/`removeFile`)、`SshjCommandExecutor`(sshj実装、TOFUホスト鍵検証、接続毎に使い捨て、コネクションプールなし)、`ShellQuote`(シングルクォートエスケープ、コマンドインジェクション対策)、`SshOperationException`
- `WordPressSshOperations`: `testConnection`/`hasAuthorProvisioningCapability`は実装済み(`wp option get siteurl`実行の成否で判定)。`resolveCategories`/`resolveTags`/`provisionAuthor`/`createOrUpdatePost`/`uploadMedia`は`UnsupportedOperationException`のスタブ
- `crypto/SshKeyGenerationService`: `ssh-keygen`サブプロセス委譲でEd25519鍵ペアを生成(どこからも呼ばれていないデッドコード)
- `WordPressAdapter`の全メソッドが`creds.isSsh()`で`WordPressSshOperations`へ分岐委譲するよう変更済み
- `build.gradle`にsshj依存、`Dockerfile`に`openssh-client`インストールを追加済み

未着手(本ドキュメントのスコープ):

- 永続化層(`SiteService`)がSSHフィールドを一切読み書きしない(`buildCredentialsFromMap`/`requiredCredentialKeys`がレガシー3フィールド固定)
- SSH鍵ペア生成をUIから呼び出す手段がない(エンドポイント未実装)
- ホスト鍵fingerprintのTOFU(Trust On First Use)観測結果を永続化する経路がない
- `WordPressSshOperations`の5メソッドのwp-cli実装
- フロントエンド(サイト登録・編集フォーム)にSSH関連の入力欄がない
- テスト一式(`ssh/`パッケージ、`SshKeyGenerationService`、`SiteService`のSSH分岐)

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 認証情報の保存方式 | 既存の汎用`credentials` Map(暗号化JSON blob)にSSHフィールドをそのまま追加する。マイグレーション不要(`SiteRegisterRequest`/`SiteUpdateRequest`の`credentials`は元々`Map<String,String>`で自由形式のため、DTO変更も不要) |
| 鍵ペアの生成・配布方式 | サーバー側で生成する(既存`SshKeyGenerationService`を使用)。新規`POST /api/sites/ssh-keypair`エンドポイントで生成し`{publicKeyLine, privateKeyPem}`を返す。**秘密鍵はレスポンスとしてフロントの状態に一時保持し、登録/編集リクエストの`credentials.sshPrivateKeyPem`として送信する**(サーバー側には保存せず、都度生成のみ)。公開鍵はUI上にコピー用テキストとして表示し、ユーザーが手動でリモートサーバーの対象ユーザーの`~/.ssh/authorized_keys`に追記する(自動配置は行わない。外部サーバーへの書き込み手段がそもそもないため) |
| ホスト鍵検証(TOFU) | 登録時、`sshHostKeyFingerprint`が未入力であれば`SshjCommandExecutor`は初回接続で提示された鍵を無条件で受理する(TOFU)。`ConnectionCheckResult`に`observedHostKeyFingerprint`(nullable)を追加し、WordPress SSHサイトの疎通確認成功時にのみ値を設定する。`SiteService`は、登録時および再疎通確認時に、保存済み`sshHostKeyFingerprint`が空でこの値が返ってきた場合、credentialsに書き戻して永続化する(以後の接続はfingerprint完全一致のみ許可、なりすまし検知)。fingerprint不一致時は`SshOperationException`で明示的に失敗させる(`SshjCommandExecutor`に実装済み) |
| `WordPressSshOperations`の未実装メソッド | 本ドキュメントのスコープ内で実装する(下記「wp-cli操作マッピング」参照) |
| REST/SSHの切替粒度 | サイト単位。1サイトにつき`transport`は`"REST"`固定 or `"SSH"`固定で、混在は不可(既存の`isSsh()`分岐と整合) |
| `provisionCategories`/`Tags`検索の一致条件 | REST版(`findOrCreateTerm`)と同じく、大文字小文字を無視した完全一致のみ既存項目として扱う。部分一致した場合でも名前が完全一致しなければ新規作成する |
| バリデーション | `SiteService.requiredCredentialKeys`を`transport`の値で分岐させる(`REST`: `baseUrl`/`username`/`appPassword`、`SSH`: `baseUrl`/`transport`/`sshHost`/`sshUser`/`wpPath`/`sshPrivateKeyPem`。`sshPort`/`sshHostKeyFingerprint`は任意) |
| managedサイトとの関係 | managed(自動構築)WordPressは常にREST transport固定(現状通り)。SSH transportは外部登録サイトのみを対象とする |

## アーキテクチャ・実装詳細

### 全体フロー(サイト登録時)

```
サイト登録フォーム(SiteCreationPanel.tsx)
  transport選択(REST/SSH)でSSHを選ぶと専用フィールドを表示
    ├─ 「鍵ペアを生成」ボタン
    │    ↓ POST /api/sites/ssh-keypair
    │    SiteController.generateSshKeyPair()
    │    ↓ adminAuthorizationService.requireAdmin()
    │    SshKeyGenerationService.generateEd25519(comment)
    │    ↓ レスポンス { publicKeyLine, privateKeyPem }
    │    フォーム側でprivateKeyPemを状態保持(送信までのみ)、publicKeyLineを画面表示
    │    (ユーザーがコピーしてリモートのauthorized_keysに手動追記)
    ├─ sshHost / sshPort / sshUser / wpPath を入力
    └─ 通常の「登録」を押下
         ↓ POST /api/sites (既存、変更不要。credentialsは自由形式map)
         SiteService.register()
           buildCredentialsFromMap → WordPressCredentials(transport=SSH, ...)
           provisioningService.provisionSite() → resolveCategories/resolveTags/provisionAuthorをwp-cli実行
           testConnection() → WordPressSshOperations.testConnection()
             初回接続fingerprintをConnectionCheckResult.observedHostKeyFingerprint()で受け取り
             sshHostKeyFingerprintが未設定ならcredentialsに書き戻して保存
```

### `CmsCredentials.WordPressCredentials`(変更なし、実装済み)

既に`transport`/`sshHost`/`sshPort`/`sshUser`/`wpPath`/`sshPrivateKeyPem`/`sshHostKeyFingerprint`と`isSsh()`が実装済み。今回の変更は不要。

### `ConnectionCheckResult`に`observedHostKeyFingerprint`を追加

```java
public record ConnectionCheckResult(boolean ok, String failureReason, String observedHostKeyFingerprint) {
    public static ConnectionCheckResult success() {
        return new ConnectionCheckResult(true, null, null);
    }
    public static ConnectionCheckResult success(String observedHostKeyFingerprint) {
        return new ConnectionCheckResult(true, null, observedHostKeyFingerprint);
    }
    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason, null);
    }
}
```

既存の`success()`/`failure()`呼び出し(`MicroCmsAdapter`/`WordPressAdapter`)は静的ファクトリ経由のみのため変更不要。`WordPressSshOperations.testConnection`だけ`success(observedFingerprint)`を使うよう変更する。

### `WordPressSshOperations.testConnection`の変更(fingerprint取得)

`SshCommandExecutor.exec`は既に`SshCommandResult.observedHostKeyFingerprint()`を返しているため、`WordPressSshOperations.testConnection`でこれを`ConnectionCheckResult`に橋渡しするだけでよい:

```java
public ConnectionCheckResult testConnection(WordPressCredentials creds) {
    try {
        SshCommandResult result = exec(creds, wpCli(creds, "option get siteurl --format=json"));
        if (result.ok()) {
            return ConnectionCheckResult.success(result.observedHostKeyFingerprint());
        }
        return ConnectionCheckResult.failure(firstLine(result.stderr(), result.stdout()));
    } catch (SshOperationException e) {
        ...
    }
}
```

### `SiteService`の変更

- `requiredCredentialKeys(CmsType, Map<String,String>)`: 引数にcredentialsを追加し、WORDPRESSの場合`transport`で分岐
- `buildCredentialsFromMap`: WORDPRESSの場合、SSHフィールドも渡すよう7引数コンストラクタを使用(`transport`未指定時は`"REST"`扱い)
- `register()`/`checkConnection()`: `testConnection`の戻り値に`observedHostKeyFingerprint`があり、かつ保存済みcredentialsの`sshHostKeyFingerprint`が空の場合、credentials mapへ書き戻して`credentialCipher.encrypt`し直し保存する(`checkConnection`は現状`readOnly`のトランザクションのため、書き戻しが発生するパスのみ通常トランザクションに変更する必要がある)
- `sshPort`は文字列→`Integer`変換(空/nullなら`null`、`WordPressSshOperations.connectionParams`が既定22にフォールバック済み)

### 新規エンドポイント: `POST /api/sites/ssh-keypair`

```java
@PostMapping("/ssh-keypair")
public SshKeyPairResponse generateSshKeyPair(@RequestBody(required = false) SshKeyPairRequest request) {
    adminAuthorizationService.requireAdmin();
    String comment = request != null && request.comment() != null ? request.comment() : "letsblog";
    return SshKeyPairResponse.from(sshKeyGenerationService.generateEd25519(comment));
}
```

`dto/SshKeyPairRequest.java`: `record SshKeyPairRequest(String comment) {}`
`dto/SshKeyPairResponse.java`: `record SshKeyPairResponse(String publicKeyLine, String privateKeyPem) {}`

DBへの永続化は行わない(ステートレス。登録リクエスト側で`credentials.sshPrivateKeyPem`として送られてきたものだけが保存される)。

### `WordPressSshOperations`: wp-cli操作マッピング

いずれも`ShellQuote.single()`で動的値をエスケープする。HTML本文等の大きな/特殊文字を含む値は、既存の`SshCommandExecutor.exec(params, command, stdin)`の`stdin`経由(wp-cliの`-`ファイル引数=標準入力読み込み)で渡し、シェル引数には展開しない。

- **resolveCategories / resolveTags**(taxonomy: `category` / `post_tag`):
  1. `wp term list <taxonomy> --search=<name> --field=name,term_id --format=json --path=<wpPath>` を実行し、結果をJSONパースして`name`が大文字小文字無視で完全一致する項目を探す
  2. 見つからなければ`wp term create <taxonomy> <name> --porcelain --path=<wpPath>`(porcelain = IDのみ標準出力)で作成
- **provisionAuthor**:
  1. `wp user list --search=<email> --field=ID --format=json --path=<wpPath>` で既存ユーザーを検索
  2. 存在すれば`wp user update <id> --display_name=<> --first_name=<> --last_name=<> --user_url=<> --description=<> --role=<role> --path=<wpPath>`(未指定フィールドは省略)
  3. 存在しなければ`wp user create <username> <email> --role=<role> --user_pass=<random> --porcelain --path=<wpPath>`で作成し、作成後に2と同じプロフィール更新コマンドを追加実行(REST版の`profileBody`相当のフィールドをすべて`user create`で一度に渡せないため2段階になる)
  4. `409`相当(REST版の重複検知)に対応するwp-cliの終了コードは存在しないため、作成コマンド失敗時のstderrに"already registered"等が含まれる場合は再検索してフォールバックする
- **createOrUpdatePost**:
  1. 新規: `wp post create - --post_title=<title> --post_status=<status> [--post_name=<slug>] --porcelain --path=<wpPath>`(標準入力=`htmlContent`)でID取得
  2. 更新: `wp post update <id> - --post_title=<title> --post_status=<status> --porcelain --path=<wpPath>`(標準入力=`htmlContent`)
  3. カテゴリ/タグは`--post_category=<カンマ区切りterm_id>`・`--tax_input='{"post_tag":[<term_id>,...]}'`相当のオプションを付与(正確なフラグ仕様は実装時にwp-cli実機で検証する)
  4. 上記いずれの後も`wp post get <id> --field=guid,post_status --format=json --path=<wpPath>`で`PostResult(id, link, status)`を組み立てる(`wp post create/update`はID以外の情報をporcelain出力しないため)
- **uploadMedia**:
  1. `SshCommandExecutor.putFile`でリモートの一時パス(例: `/tmp/letsblog-media-<uuid>-<filename>`)へバイト列をSFTP転送
  2. `wp media import <一時パス> --porcelain --path=<wpPath>`でアタッチメントID取得
  3. `wp post get <id> --field=guid --path=<wpPath>`でURL取得し`MediaUploadResult(id, url)`を組み立てる
  4. `finally`で`SshCommandExecutor.removeFile`によりリモート一時ファイルを削除(失敗してもログのみ、既存の`removeFile`の設計方針と同じ)

### フロントエンド

`web/src/app/sites/SiteCreationPanel.tsx`(および`SiteEditForm.tsx`。編集は非managedサイトのみ許可という既存制約はそのまま維持):

- WordPress選択時に「接続方式」ラジオボタン(REST / SSH)を追加(既定REST、後方互換)
- SSH選択時に表示するフィールド: SSHホスト、SSHポート(既定22)、SSHユーザー、wp-cliパス(`wpPath`)、秘密鍵(生成ボタン経由で自動入力、読み取り専用表示)、ホスト鍵fingerprint(任意入力、既定空欄=TOFU)
- 「鍵ペアを生成」ボタン押下で`apiClient.ts`に追加する`generateSshKeyPair()`を呼び出し、返却された`publicKeyLine`を`<textarea readOnly>`で表示(コピーボタン付き)、`privateKeyPem`はフォームの隠しstateに保持して送信時に`credentials.sshPrivateKeyPem`へ含める(画面には表示しない、あるいは折りたたみ表示にして誤ってスクリーンショット等で漏洩しないよう配慮)
- 登録/更新自体は既存の`registerSite`/`updateSite`(`credentials`が自由形式mapのため型変更不要)

## スコープ・実装項目

実装対象:

- [ ] `cms/ConnectionCheckResult.java`: `observedHostKeyFingerprint`追加
- [ ] `cms/ssh/WordPressSshOperations.java`: `testConnection`のfingerprint橋渡し、`resolveCategories`/`resolveTags`/`provisionAuthor`/`createOrUpdatePost`/`uploadMedia`のwp-cli実装
- [ ] `service/SiteService.java`: `requiredCredentialKeys`/`buildCredentialsFromMap`のSSH分岐、fingerprint書き戻し
- [ ] `dto/SshKeyPairRequest.java`・`dto/SshKeyPairResponse.java`(新規)
- [ ] `controller/SiteController.java`: `POST /api/sites/ssh-keypair`(新規、`SshKeyGenerationService`注入)
- [ ] `web/src/lib/apiClient.ts`: `generateSshKeyPair()`
- [ ] `web/src/app/sites/SiteCreationPanel.tsx`・`SiteEditForm.tsx`: transport選択・SSH入力欄・鍵生成UI

対象外・スコープ外:

- 外部サーバーへの公開鍵の自動配置(常にユーザーによる手動コピー)
- Phase10-03の環境間同期を外部登録サイトへ拡張すること(別件、将来検討)
- SSH接続のコネクションプーリング・鍵のローテーション機能
- 複数のSSH鍵/踏み台サーバー経由(ProxyJump)といった高度な接続トポロジ

## 実装順序

1. `ConnectionCheckResult`にfingerprintフィールド追加 → `WordPressSshOperations.testConnection`更新
2. `SiteService`の永続化配線(`requiredCredentialKeys`/`buildCredentialsFromMap`/fingerprint書き戻し)。この時点でSSHサイトの登録・疎通確認・再疎通確認がE2Eで通る(カテゴリ/タグ/著者/投稿/メディアは未実装のため`UnsupportedOperationException`のまま部分失敗として扱われる)
3. `WordPressSshOperations`の残りメソッド(wp-cli実装): resolveCategories/resolveTags → provisionAuthor → createOrUpdatePost → uploadMedia の順(REST版の実装依存順序と揃える)
4. `POST /api/sites/ssh-keypair`エンドポイント
5. フロントエンド(SiteCreationPanel → SiteEditForm)
6. テスト整備・実機検証

## テスト整備

- `ShellQuoteTest`: シングルクォート・特殊文字(`$`, `` ` ``, `;`, 改行等)のエスケープ
- `SshKeyGenerationServiceTest`: 生成された鍵ペアが有効なOpenSSH形式であること、一時ディレクトリが削除されること
- `WordPressSshOperationsTest`(`SshCommandExecutor`をモック化): 各メソッドが期待するコマンド文字列・stdinを組み立てて`exec`/`putFile`/`removeFile`を呼ぶこと、`testConnection`成功時に`observedHostKeyFingerprint`が伝播すること
- `SiteServiceTest`: SSH transportの`credentials`で`register`/`update`が通ること、必須キー欠落時に例外、fingerprint未設定→初回成功で書き戻されること、2回目以降は書き戻し済みfingerprintのまま変わらないこと
- `SshjCommandExecutorTest`: 可能であれば埋め込みSSHサーバ(Apache MINA SSHD等)を使った結合テストを検討(実機のSSHサーバーが必要なため、モックのみで完結しない箇所は実機検証に回す)

## 実機検証

1. Cloudflare等でREST APIがブロックされた(または単純にSSHのみ提供された)実サーバーを用意し、SSH transportでサイト登録
2. 「鍵ペアを生成」→公開鍵をリモートの`authorized_keys`に手動追記→登録実行→疎通確認成功を確認
3. 記事作成・画像アップロード・カテゴリ/タグ付与が実際にWordPress管理画面に反映されることを確認
4. サイト編集画面から再疎通確認を実行し、2回目以降もfingerprint検証で正しく成功することを確認
5. リモートの`authorized_keys`の鍵を削除、または`sshHostKeyFingerprint`を意図的に不一致な値に書き換えて、接続が明示的に失敗することを確認(なりすまし検知の動作確認)

## 未決事項・将来検討

- `provisionAuthor`のwp-cli版で、REST版の409重複検知に相当する挙動をどこまで作り込むか(stderrの文字列マッチに依存するため脆い可能性がある)
- `createOrUpdatePost`のカテゴリ/タグ付与フラグ(`--post_category`/`--tax_input`)の正確な仕様確認(実装時にwp-cli実機で要検証)
- SSH秘密鍵のローテーション・失効(サイト編集での鍵再生成)フロー
- 踏み台サーバー(ProxyJump)経由接続への対応要否
- 本機能の`ssh/`パッケージ・`SshKeyGenerationService`をPhase10-03(環境間同期)の外部サイト対応に転用するかどうか
