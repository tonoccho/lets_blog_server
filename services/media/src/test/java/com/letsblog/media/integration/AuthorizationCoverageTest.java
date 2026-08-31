package com.letsblog.media.integration;

import com.letsblog.common.testfixtures.AuthorizationCoverageContract;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 認可チェックを持たないエンドポイントが増えていないことを保証する(issue #830)。
 *
 * <p>Spring コンテキストを起動しない純粋な静的解析なので、DBもコンテナも要らない。
 */
class AuthorizationCoverageTest {

    /**
     * media-service に認可チェックの無いエンドポイントは残っていない(issue #830)。
     *
     * <p>最後まで残っていた {@code MediaController#upload} は、publishing-service の内部ブリッジへ
     * {@code sites/{site}/project-id} を足してサイトキーからプロジェクトを逆引きできるようにし、
     * {@code requireProjectMemberOrAdminForResource} で塞いだ。
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of();

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("media", KNOWN_UNAUTHORIZED);
    }
}
