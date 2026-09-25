package com.letsblog.publishing.integration;

import com.letsblog.common.testfixtures.AuthorizationCoverageContract;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 認可チェックを持たないエンドポイントが増えていないことを保証する(issue #830)。
 *
 * <p>#830 の調査で、認可チェックを一切持たないエンドポイントが内部ブリッジを除いて
 * 全サービス合計56件あることが分かった。個々について「認証のみでよいか、認可が必要か」を
 * 決めるのは製品判断を伴い一度には片付かないため、現状を許可リストで固定し、
 * <b>新しく増えることだけを止める</b>。
 *
 * <p>下の {@code KNOWN_UNAUTHORIZED} から項目を減らせたら、このリストからも消すこと
 * (残したままだとテストが「解消済みなのに残っている」と教えてくれる)。
 *
 * <p>Spring コンテキストを起動しない純粋な静的解析なので、DBもコンテナも要らない。
 */
class AuthorizationCoverageTest {

    /**
     * publishing-service に認可チェックの無いエンドポイントは残っていない。
     *
     * <p>#830 時点では {@code PostController#publish} / {@code #delete} /
     * {@code TaxonomyController#resolve} の3件が該当していた。当時は
     * 「{@code SiteBridge} が {@code projectId} を持たないため掛けられない」と記録していたが、
     * {@code ProjectServiceClient#findProjectIdBySiteId} で逆引きできるため、
     * いずれも {@code requireProjectMemberOrAdminForSite} で塞いだ。
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of();

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("publishing", KNOWN_UNAUTHORIZED);
    }
}
