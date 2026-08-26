package com.letsblog.project.controller;

import com.letsblog.project.dto.SiteCredentialsResponse;
import com.letsblog.project.service.SiteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * サイトのCMS認証情報(暗号化保存、ここが正)をサービス間で取得するための内部API(issue #577受入基準)。
 * 想定利用者はpublishing-service(C6、issue #575。本Issue時点では未抽出)で、抽出後は
 * issue #581(C12)のSyncServiceClient/ServiceTokenClient(クライアント資格情報)経由で呼び出す想定。
 *
 * <p>本stage時点では、issue #567(B9)のクライアント資格情報トークン発行は実装済みだが、
 * サービス自身が受け取ったトークンが実際にサービス用(ユーザー用ではない)であることを検証する
 * 仕組みはリポジトリ全体でまだ実装されていない(#567はトークン取得側=呼び出し元のみ)。
 * そのため本エンドポイントは、project-serviceのSecurityConfigで{@code /api/internal/**}を
 * 認証必須(JWT必須、匿名拒否)にする以上の追加の認可チェックは行わない。厳密なサービス間認証の
 * 強制は、認可マトリクス整備(B10、issue #568)またはpublishing-service抽出(C6)時のfollow-upとする。
 */
@RestController
public class SiteCredentialsInternalController {

    private final SiteService siteService;

    public SiteCredentialsInternalController(SiteService siteService) {
        this.siteService = siteService;
    }

    @GetMapping("/api/internal/project/sites/{siteKey}/credentials")
    public SiteCredentialsResponse getCredentials(@PathVariable String siteKey) {
        return SiteCredentialsResponse.from(siteService.getResolvedCredentials(siteKey));
    }
}
