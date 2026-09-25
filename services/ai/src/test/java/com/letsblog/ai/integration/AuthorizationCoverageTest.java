package com.letsblog.ai.integration;

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
     * ai-service に認可チェックの無いエンドポイントは残っていない(issue #830)。
     *
     * <ul>
     *   <li>{@code AiController} の draft / ask / proofread / section — 利用者自身の入力からの
     *       生成で保存済みリソースに触れないため「認可不要」と判断し、理由をJavadocへ記録した</li>
     *   <li>{@code AiController#tags} — projectId 指定時は既存タグを読むので
     *       {@code requireProjectMemberOrAdmin} を掛けた</li>
     *   <li>{@code GenerationJobController} の list / get — 共通ダッシュボードの表示用。
     *       generation_jobs に所有者列が無く利用者ごとに絞れないことをギャップとして記録した</li>
     *   <li>{@code GenerationJobController} の create / update と
     *       {@code InternalAiGenerationController#generate} — いずれもコンテナ間専用なので
     *       {@code /api/internal/ai/**} へ移し、外部からの到達経路を無くした</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of();

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("ai", KNOWN_UNAUTHORIZED);
    }
}
