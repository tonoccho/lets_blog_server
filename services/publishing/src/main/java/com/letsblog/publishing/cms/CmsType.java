package com.letsblog.publishing.cms;

/**
 * 対応するCMSの種別。CmsAdapterFactoryが実装を選択する際のキーとして使う。
 *
 * WordPress以外のCMS対応(旧: microCMS)はissue #374で廃止された。新たなCMSに対応する場合は
 * このenumへ値を追加し、CmsAdapter実装を1つ追加してCmsAdapterFactoryに登録すればよい
 * (アーキテクチャ自体は複数CMS対応を前提として維持している)。
 */
public enum CmsType {
    WORDPRESS("WordPress");

    private final String displayName;

    CmsType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
