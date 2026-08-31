package com.letsblog.publishing.integration;

import com.letsblog.common.testfixtures.AuthorizationCoverageContract;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 認可チェックを持たないエンドポイントが増えていないことを保証する(issue #830)。
 *
 * <p>#830 の調査で、認可チェックを一切持たないエンドポイントが内部ブリッジを除いて
 * 全サービス合計98件あることが分かった。個々について「認証のみでよいか、認可が必要か」を
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
     * 現時点で認可チェックを持たないエンドポイント(issue #830 時点)。
     *
     * <ul>
     *   <li>{@code PostController#publish} / {@code #delete} — WordPress への投稿公開・削除。
     *       **本来は認可が必要**。ただし {@code ProjectServiceClient.SiteBridge} が
     *       {@code projectId} を持たないため、{@code requireProjectMemberOrAdmin} を掛けるには
     *       project-service の内部ブリッジに projectId を載せる変更が要る(#830 で対応)</li>
     *   <li>{@code TaxonomyController#resolve} — CMS のカテゴリ/タグ解決。要否は #830 で判断する</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of(
            "PostController#publish",
            "PostController#delete",
            "TaxonomyController#resolve");

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("publishing", KNOWN_UNAUTHORIZED);
    }
}
