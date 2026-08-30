package com.letsblog.content.service;

/**
 * PostController#lookupBySlugが使う、siteKeyがlegacy-api側に登録されていない場合の例外
 * (legacy-apiのSiteNotFoundExceptionと同じ形。Site domain自体はcontent-serviceに存在しない)。
 */
public class SiteNotFoundException extends RuntimeException {
    public SiteNotFoundException(String message) {
        super(message);
    }
}
