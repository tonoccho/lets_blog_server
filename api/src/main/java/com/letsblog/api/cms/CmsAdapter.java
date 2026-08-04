package com.letsblog.api.cms;

import java.util.List;
import java.util.Optional;

/**
 * CMS(WordPress、microCMS等)への操作を抽象化するインターフェース。
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
}
