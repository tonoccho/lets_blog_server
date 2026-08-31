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
     * media-service に残る唯一の無認可エンドポイント(issue #830)。
     *
     * <p>{@code MediaController#upload} は {@code site} キーで指定した CMS のメディアライブラリへ
     * 直接ファイルをアップロードする。<b>サイトが属するプロジェクトのメンバーに限定すべきだが、
     * media-service には site キーからプロジェクトを引く手段が無い</b>
     * (publishing-service の {@code /api/internal/publishing/**} は {@code sites/{site}/media} と
     * {@code projects/{projectId}/media-scan}・{@code media/{mediaId}} しか公開しておらず、
     * site→project の逆引きが無い)。publishing 側にブリッジを足す変更を伴うため、
     * このスライスのスコープからは外した。
     *
     * <p>ダイアグラム・生成画像の12件は {@code AdminAuthorizationService} に
     * {@code requireProjectMemberOrAdmin} を追加して塞いだ。
     */
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of("MediaController#upload");

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("media", KNOWN_UNAUTHORIZED);
    }
}
