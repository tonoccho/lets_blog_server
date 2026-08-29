package com.letsblog.platform.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、SystemSettingService/AppSettingServiceが必要とする
 * requireAdminのみを移設する(issue #693)。実際のadmin判定はCurrentActorService経由で
 * identity-serviceへ委ねる。
 *
 * <p>requireAuthenticated()は、legacy-api版のSecurityConfig({@code anyRequest().authenticated()})が
 * 全経路で強制していた「最低限ログイン済みであること」を、本サービスのSecurityConfigが当時は全経路
 * permitAll(他の抽出済みサービスと同じ構成)だったために失われていた分を補うものとして追加した
 * (issue #693のレビュー指摘。SystemSettingService#getBraveSearchApiKeyStatus参照。admin権限までは
 * 要求せず、有効なJWTが提示されていることのみを要求する)。
 *
 * <p>issue #705で本サービスのSecurityConfig自体を「公開パスを除きJWT必須」へ戻したため、
 * HTTP経由の未認証リクエストはコントローラ到達前に401で弾かれるようになった。requireAuthenticated()は
 * 多層防御としてそのまま残す(SecurityConfigの公開パス設定が将来緩んだ場合の保険)。
 */
@Service
public class AdminAuthorizationService {

    private final CurrentActorService currentActorService;

    public AdminAuthorizationService(CurrentActorService currentActorService) {
        this.currentActorService = currentActorService;
    }

    public void requireAdmin() {
        if (!currentActorService.isAdmin()) {
            throw new ForbiddenException("この操作にはadmin権限が必要です");
        }
    }

    public void requireAuthenticated() {
        if (!currentActorService.isAuthenticated()) {
            throw new ForbiddenException("ログインが必要です");
        }
    }
}
