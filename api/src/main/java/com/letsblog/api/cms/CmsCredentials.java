package com.letsblog.api.cms;

/**
 * CMSへの接続情報。CMS種別ごとに必要なフィールドが異なるため、sealed interface + record で表現する。
 */
public sealed interface CmsCredentials {

    CmsType cmsType();

    record WordPressCredentials(
            String baseUrl,
            String username,
            String transport,
            String sshHost,
            Integer sshPort,
            String sshUser,
            String wpPath,
            String sshPrivateKeyPem,
            String sshHostKeyFingerprint,
            String wpSlug
    ) implements CmsCredentials {
        @Override
        public CmsType cmsType() {
            return CmsType.WORDPRESS;
        }

        public WordPressCredentials(String baseUrl, String username, String transport) {
            this(baseUrl, username, transport, null, null, null, null, null, null, null);
        }

        public boolean isSsh() {
            return "SSH".equalsIgnoreCase(transport);
        }

        /**
         * 自動構築(managed)WordPressサイト向け。常駐wordpressコンテナ内の内部限定
         * プロビジョニングエージェント(wordpress/provision-agent)経由でwp-cliを実行する。
         * wpSlugはそのサイトディレクトリ({@code /var/www/html/sites/{wpSlug}})の識別子。
         */
        public boolean isAgent() {
            return "AGENT".equalsIgnoreCase(transport);
        }
    }
}
