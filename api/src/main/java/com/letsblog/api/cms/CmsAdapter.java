package com.letsblog.api.cms;

import java.util.List;

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
}
