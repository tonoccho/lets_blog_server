package com.letsblog.api.cms;

/**
 * 対応するCMSの種別。CmsAdapterFactoryが実装を選択する際のキーとして使う。
 */
public enum CmsType {
    WORDPRESS("WordPress"),
    MICROCMS("microCMS");

    private final String displayName;

    CmsType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
