package com.letsblog.publishing.cms;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CMS(現在はWordPressのみ対応)への操作を抽象化するインターフェース。
 * 新しいCMSへ対応する場合は、この実装を追加した上でCmsAdapterFactoryに登録すればよい。
 */
public interface CmsAdapter {

    /**
     * このアダプタが対応するCMS種別を返す。CmsAdapterFactoryが実装解決に使う。
     */
    CmsType supportedType();

    /**
     * 投稿を新規作成、または既存投稿(existingPostId指定時)を更新する。
     */
    PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId);

    /**
     * 指定IDの投稿がCMS側に実在するかどうかを判定する(読み取り専用)。API側(lets_blog.posts)が
     * 記憶している投稿IDは、CMS側で当該投稿(と、投稿時に一緒にアップロードした画像)が
     * 削除されると実在しなくなることがある。呼び出し側はこれを使って、前回アップロード済み画像の
     * 再利用キャッシュを信頼してよいか判断する(issue #493。投稿自体の作成/更新時のフォールバックは
     * createOrUpdatePost実装内で個別に行う)。対応しないCMSやID未指定時は既定でtrueを返す
     * (判定不能時は安全側=従来どおりの再利用を許容する)。
     */
    default boolean postExists(CmsCredentials credentials, String postId) {
        return true;
    }

    /**
     * 指定スラッグの既存投稿(post_type=post)のIDをCMS側に照会する(読み取り専用、issue #1431)。
     * 公開済みに加え下書き・非公開・予約・レビュー待ちを対象とし、ゴミ箱は対象外とする。
     * ローカルDBに記録が無くてもCMS側の同スラッグ記事を更新対象として同定するために使う。
     * 照会に失敗した場合は例外を投げる(呼び出し側は重複を作らないため新規作成へ進んではならない)。
     * 対応しないCMSは既定で空(=照会できない)を返す。
     */
    default List<String> findPostIdsBySlug(CmsCredentials credentials, String slug) {
        return List.of();
    }

    /**
     * 指定IDのメディア(添付ファイル)がCMS側に実在するかどうかを判定する(読み取り専用)。
     * 前回アップロード済み画像の再利用キャッシュは、投稿本体の実在確認(postExists、issue #493)
     * だけでは不十分で、投稿は残っていてもメディアライブラリから当該画像だけが個別に削除されている
     * ケースを検知できない(issue #495)。呼び出し側はsha256が一致し再利用を検討する場合に限り
     * これを使って再利用の可否を判断する。対応しないCMSやID未指定時は既定でtrueを返す
     * (判定不能時は安全側=従来どおりの再利用を許容する)。
     */
    default boolean mediaExists(CmsCredentials credentials, String mediaId) {
        return true;
    }

    /**
     * アップロード時にWordPress側へ記録した内容ハッシュ(sha256)で、既存メディアをまとめて照会する
     * (読み取り専用、issue #1432)。戻り値はsha256→メディア。ゴミ箱・削除済みのメディアは含めない。
     * 呼び出し側は画像の枚数によらず1回の呼び出しで済ませられる。照会に失敗した場合は例外を投げる
     * (呼び出し側は従来どおり新規アップロードで続行してよい)。対応しないCMSは既定で空を返す。
     */
    default Map<String, MediaUploadResult> findMediaBySha256(CmsCredentials credentials,
                                                            Collection<String> sha256s) {
        return Map.of();
    }

    /**
     * メディアライブラリへ画像をアップロードする。
     */
    MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data);

    /**
     * カテゴリ名のリストをID解決する。存在しなければ作成する。
     */
    List<String> resolveCategories(CmsCredentials credentials, List<String> names);

    /**
     * タグ名のリストをID解決する。存在しなければ作成する。
     */
    List<String> resolveTags(CmsCredentials credentials, List<String> names);

    /**
     * 認証情報が有効かどうかを軽量なリクエストで確認する。例外は投げず結果を返す。
     */
    ConnectionCheckResult testConnection(CmsCredentials credentials);

    /**
     * デフォルトカテゴリを作成(または既存のものを取得)し、IDを返す。
     */
    String provisionDefaultCategory(CmsCredentials credentials);

    /**
     * デフォルトタグを作成(または既存のものを取得)し、IDを返す。
     */
    String provisionDefaultTag(CmsCredentials credentials);

    /**
     * ユーザーを著者として登録する。既に存在する場合はプロフィール・ロールを更新する。
     * CMS側が著者の概念を持たない、または未対応の場合はnullを返してよい。
     */
    String provisionAuthor(CmsCredentials credentials, AuthorProvisioningRequest request);

    /**
     * この認証情報が、著者(ユーザー)の作成・更新を行うのに十分な権限を持っているかどうかを判定する。
     * CMSによっては著者という概念自体がなく判定不要なため、既定はtrueを返す。
     */
    default boolean hasAuthorProvisioningCapability(CmsCredentials credentials) {
        return true;
    }

    /**
     * SSHトランスポートで接続されたサイトにwp-cliをインストールする。
     * SSH接続に対応しない、またはCMS側にwp-cliの概念がない場合はUnsupportedOperationExceptionを投げる。
     */
    default WpCliInstallResult installWpCli(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMSはwp-cliのインストールに対応していません");
    }

    /**
     * サイトの letsblog プラグインの導入状態を返す(issue #1557)。対応しない場合はUnsupportedOperationExceptionを投げる。
     */
    default LetsblogPluginStatus letsblogPluginStatus(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMSはletsblogプラグインの導入状態の取得に対応していません");
    }

    /**
     * letsblog プラグインを(再)導入して有効化し、導入後の状態を返す(issue #1557)。
     * 対応しない場合はUnsupportedOperationExceptionを投げる。
     */
    default LetsblogPluginStatus installLetsblogPlugin(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMSはletsblogプラグインの導入に対応していません");
    }

    /**
     * タグ定義・統合CSS等をletsblogプラグインへ送り、プラグインが保存した内容のハッシュを返す(issue #1558)。
     * 送信は wp-cli だけで行う。対応しない場合はUnsupportedOperationExceptionを投げる。
     */
    default String syncLetsblogPlugin(CmsCredentials credentials, String payload, String expectedHash) {
        throw new UnsupportedOperationException("このCMSはletsblogプラグインへの同期に対応していません");
    }

    /**
     * 内容(JSON)をletsblogプラグインへ渡し、投稿を作らずに実テーマで表示する署名付きプレビューURLを返す
     * (issue #1561)。ttlSecondsがnullならプラグインの規定値。対応しない場合はUnsupportedOperationExceptionを投げる。
     */
    default SignedPreview createSignedPreview(CmsCredentials credentials, String payload, Integer ttlSeconds) {
        throw new UnsupportedOperationException("このCMSは署名付きプレビューURLの発行に対応していません");
    }

    /**
     * letsblog プラグインが使えるサイトであることを要求する(issue #1557)。導入済み以外(未導入・要更新)なら、
     * 理由と対処(再導入)を示す{@link LetsblogPluginUnavailableException}を投げる。投稿・プレビューが
     * CMSへ何かを書き込む前に呼ぶ。
     */
    default void requireLetsblogPlugin(CmsCredentials credentials) {
        LetsblogPluginStatus status = letsblogPluginStatus(credentials);
        if (!status.installed()) {
            throw new LetsblogPluginUnavailableException(status);
        }
    }

    /**
     * 投稿を削除する(WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。
     * 対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default void deletePost(CmsCredentials credentials, String postId) {
        throw new UnsupportedOperationException("このCMSは投稿削除に対応していません");
    }

    /**
     * メールアドレスに一致する既存の著者(ユーザー)IDを検索する(新規作成は行わない、読み取り専用)。
     * 投稿作成時に、投稿者に対応するCMS側ユーザーを投稿の著者として設定するために使う。
     * 見つからない場合、またはCMSが著者検索に対応しない場合は空を返す(例外は投げない)。
     */
    default Optional<String> findAuthorIdByEmail(CmsCredentials credentials, String email) {
        return Optional.empty();
    }

    /**
     * 既存のカテゴリ名一覧を取得する(読み取り専用、新規作成は行わない)。
     * 記事メタデータ提案時に、AIへ既存カテゴリの候補を提示するために使う。
     * 取得できない、またはCMSがカテゴリの概念を持たない場合は空リストを返す(例外は投げない)。
     */
    default List<String> listCategoryNames(CmsCredentials credentials) {
        return List.of();
    }

    /**
     * 既存カテゴリを親カテゴリ名付きで取得する(読み取り専用、新規作成は行わない)。
     * VSCode拡張の記事作成画面で、子カテゴリ選択時に親カテゴリを自動選択するために使う(issue #289)。
     * 親子関係を持たない、または取得できない場合は各カテゴリのparentNameをnullにして返す。
     */
    default List<CategoryOption> listCategoriesWithParents(CmsCredentials credentials) {
        return listCategoryNames(credentials).stream().map(name -> new CategoryOption(name, null)).toList();
    }

    /**
     * 既存のタグ名一覧を取得する(読み取り専用、新規作成は行わない)。
     * AIタグ提案時に、既存タグを優先して提案させるために使う(issue #525)。
     * 取得できない、またはCMSがタグの概念を持たない場合は空リストを返す(例外は投げない)。
     */
    default List<String> listTagNames(CmsCredentials credentials) {
        return List.of();
    }

    /**
     * 親カテゴリ付きのカテゴリ一覧項目。parentNameは親カテゴリが無ければnull。
     */
    record CategoryOption(String name, String parentName) {
    }

    /**
     * 投稿(post)または固定ページ(page)の一覧を取得する(読み取り専用。プロジェクト管理画面の
     * ポスト/ページ管理タブで環境間比較に使う)。対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default List<CmsPostSummary> listPosts(CmsCredentials credentials, String postType) {
        throw new UnsupportedOperationException("このCMSは投稿/ページ一覧取得に対応していません");
    }

    /**
     * 投稿または固定ページのステータスを変更する。対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default void updatePostStatus(CmsCredentials credentials, String postId, String postType, String status) {
        throw new UnsupportedOperationException("このCMSは投稿/ページのステータス変更に対応していません");
    }

    /**
     * 投稿または固定ページを削除する(WordPressの場合、既定でゴミ箱へ移動する)。
     * {@link #deletePost(CmsCredentials, String)}のpostType対応版(REST APIはpostとpageで
     * エンドポイントが異なるため必要)。既定はpostType不問で{@link #deletePost(CmsCredentials, String)}
     * に委譲する(SSH/エージェント経由はIDのみで削除できるため、この既定で十分)。
     */
    default void deletePost(CmsCredentials credentials, String postId, String postType) {
        deletePost(credentials, postId);
    }

    /**
     * 非公開(private)投稿を実際に表示するプレビュー用に、指定認証情報のユーザーとして
     * ログイン済みと同等のCookieを発行する(記事プレビューでPlaywrightのブラウザコンテキストへ
     * 注入するために使う)。wp-cli等でサーバー側のコード実行が可能な経路(managedサイトのagent
     * transport等)でのみ対応可能なため、対応しない経路はUnsupportedOperationExceptionを投げる。
     */
    default AuthCookie generateAuthCookie(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMS/接続方式は認証Cookieの発行に対応していません");
    }

    /**
     * メディアライブラリの一覧を取得する(読み取り専用、ガベージコレクション画面向け。issue #500)。
     * 対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default List<CmsMediaSummary> listMedia(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMSはメディア一覧取得に対応していません");
    }

    /**
     * 全投稿の本文・アイキャッチ・主要サイト設定からのメディア参照を収集する(読み取り専用、
     * ガベージコレクション画面向け。issue #500)。対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default CmsMediaReferenceScan scanMediaReferences(CmsCredentials credentials) {
        throw new UnsupportedOperationException("このCMSはメディア参照スキャンに対応していません");
    }

    /**
     * 指定IDのメディア(添付ファイル)を完全に削除する(ゴミ箱を経由しない物理削除、issue #500)。
     * 対応しないCMSはUnsupportedOperationExceptionを投げる。
     */
    default void deleteMedia(CmsCredentials credentials, String mediaId) {
        throw new UnsupportedOperationException("このCMSはメディア削除に対応していません");
    }
}
