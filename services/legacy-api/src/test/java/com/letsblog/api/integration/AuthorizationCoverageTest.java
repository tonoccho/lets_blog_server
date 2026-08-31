package com.letsblog.api.integration;

import com.letsblog.common.testfixtures.AuthorizationCoverageContract;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 認可チェックを持たないエンドポイントが増えていないことを保証する(issue #830)。
 *
 * <p>個々の「認証のみでよいか、認可が必要か」は製品判断を伴い一度には片付かないため、
 * 現状を許可リストで固定し<b>増えることだけを止める</b>。意図的に認証のみでよい場合は、
 * そのメソッドのコメントに {@code 認可不要: <理由>} と書けばリストに載せなくてよい。
 *
 * <p>Spring コンテキストを起動しない静的解析なので、DBもコンテナも要らない。
 */
class AuthorizationCoverageTest {

    /** 現時点で認可チェックを持たないエンドポイント(issue #830 時点)。
     * 減らせたらこのリストからも消すこと(残したままだとテストが教えてくれる)。 */
    /**
     * legacy-api に認可チェックの無いエンドポイントは残っていない(issue #830)。
     *
     * <p>{@code AiController#image} / {@code #imageOptions} は projectId 指定時に
     * プロジェクト設定を読むため {@code requireProjectMemberOrAdmin} を掛けた
     * (同じクラスの {@code generateImagePrompt} が既にそうしていたのに揃えた)。
     * {@code AuthController} の2件と {@code HealthController#health} は
     * SecurityConfig の PUBLIC_PATHS に含まれる<b>認証前に叩かれる公開パス</b>で、
     * 認可を掛けると初回セットアップ自体が不可能になる。理由は Javadoc へ記録した。
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of();

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("legacy-api", KNOWN_UNAUTHORIZED);
    }
}
