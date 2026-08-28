package com.letsblog.platform.service;

import org.springframework.stereotype.Service;

/**
 * legacy-apiのAdminAuthorizationServiceのうち、SystemSettingService/AppSettingServiceが必要とする
 * requireAdminのみを移設する(issue #693)。実際のadmin判定はCurrentActorService経由で
 * identity-serviceへ委ねる。
 *
 * <p>requireAuthenticated()は、legacy-api版のSecurityConfig({@code anyRequest().authenticated()})が
 * 全経路で強制していた「最低限ログイン済みであること」を、本サービスのSecurityConfig(全経路
 * permitAll、他の抽出済みサービスと同じ構成)へ移行した際に失われた認可の後退を補うために追加する
 * (issue #693のレビュー指摘。SystemSettingService#getBraveSearchApiKeyStatus参照。admin権限までは
 * 要求せず、有効なJWTが提示されていることのみを要求する)。
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
