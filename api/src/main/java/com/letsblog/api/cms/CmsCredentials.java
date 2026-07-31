package com.letsblog.api.cms;

/**
 * CMSへの接続情報。CMS種別ごとに必要なフィールドが異なるため、sealed interface + record で表現する。
 */
public sealed interface CmsCredentials {

    CmsType cmsType();

    record WordPressCredentials(
            String baseUrl,
            String username,
            String appPassword,
            String transport,
            String sshHost,
            Integer sshPort,
            String sshUser,
            String wpPath,
            String sshPrivateKeyPem,
            String sshHostKeyFingerprint
    ) implements CmsCredentials {
        @Override
        public CmsType cmsType() {
            return CmsType.WORDPRESS;
        }

        public WordPressCredentials(String baseUrl, String username, String appPassword) {
            this(baseUrl, username, appPassword, "REST", null, null, null, null, null, null);
        }

        public boolean isSsh() {
            return "SSH".equalsIgnoreCase(transport);
        }
    }

    record MicroCmsCredentials(
            String serviceId,
            String apiKey,
            String managementApiKey,
            String postsEndpoint,
            String categoriesEndpoint,
            String tagsEndpoint
    ) implements CmsCredentials {
        @Override
        public CmsType cmsType() {
            return CmsType.MICROCMS;
        }
    }
}
