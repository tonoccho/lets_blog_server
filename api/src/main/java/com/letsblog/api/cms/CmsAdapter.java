package com.letsblog.api.cms;

import java.util.List;

/**
 * CMS(WordPress等)への操作を抽象化するインターフェース。
 * 将来WordPress以外のCMSを追加する場合は、この実装を追加すればよい。
 */
public interface CmsAdapter {

    /**
     * 投稿を新規作成、または既存投稿(existingPostId指定時)を更新する。
     */
    PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, Long existingPostId);

    /**
     * メディアライブラリへ画像をアップロードする。
     */
    MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data);

    /**
     * カテゴリ名のリストをID解決する。存在しなければ作成する。
     */
    List<Long> resolveCategories(CmsCredentials credentials, List<String> names);

    /**
     * タグ名のリストをID解決する。存在しなければ作成する。
     */
    List<Long> resolveTags(CmsCredentials credentials, List<String> names);
}
