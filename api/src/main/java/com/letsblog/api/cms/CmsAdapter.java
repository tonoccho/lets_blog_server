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
     * 認証情報が有効かどうかを軽量なリクエストで確認する。例外は投げず成否をbooleanで返す。
     */
    boolean testConnection(CmsCredentials credentials);

    /**
     * デフォルトカテゴリを作成(または既存のものを取得)し、IDを返す。
     */
    String provisionDefaultCategory(CmsCredentials credentials);

    /**
     * デフォルトタグを作成(または既存のものを取得)し、IDを返す。
     */
    String provisionDefaultTag(CmsCredentials credentials);

    /**
     * サイト登録者を著者として登録(または既存の著者を取得)し、IDを返す。
     * CMS側が著者の概念を持たない、または未対応の場合はnullを返してよい。
     */
    String provisionAuthor(CmsCredentials credentials, String email);
}
