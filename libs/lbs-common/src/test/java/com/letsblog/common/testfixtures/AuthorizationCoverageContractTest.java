package com.letsblog.common.testfixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ラチェット自体の挙動を固定する(issue #830)。
 * 実サービスに対する許可リストは各サービスのテストが持つ。
 */
class AuthorizationCoverageContractTest {

    /**
     * 件数が少なく変化を追いやすいので検証台にする。publishing は #830 で無認可がゼロになり、
     * 「1件取り除いて失敗を確かめる」検証が成り立たなくなったため content に移した。
     */
    private static final String SERVICE = "content";

    @Test
    @DisplayName("現状の一覧をそのまま許可リストにすれば通る")
    void 現状と一致すれば通る() {
        Set<String> current = AuthorizationCoverageContract.currentUnauthorized(SERVICE);
        assertDoesNotThrow(
                () -> AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints(SERVICE, current));
    }

    @Test
    @DisplayName("許可リストに無い無認可エンドポイントがあれば失敗する(新規の付け忘れ検知)")
    void 新規の無認可は失敗する() {
        Set<String> current = new TreeSet<>(AuthorizationCoverageContract.currentUnauthorized(SERVICE));
        String removed = current.iterator().next();
        current.remove(removed);

        AssertionError e = assertThrows(AssertionError.class,
                () -> AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints(SERVICE, current));
        assertTrue(e.getMessage().contains("新たに認可チェックの無いエンドポイントが増えました"), e.getMessage());
        assertTrue(e.getMessage().contains(removed), e.getMessage());
    }

    @Test
    @DisplayName("解消済みなのに許可リストに残っていれば失敗する(リストの陳腐化防止)")
    void 解消済みが残っていれば失敗する() {
        Set<String> current = new TreeSet<>(AuthorizationCoverageContract.currentUnauthorized(SERVICE));
        current.add("GhostController#alreadyFixed");

        AssertionError e = assertThrows(AssertionError.class,
                () -> AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints(SERVICE, current));
        assertTrue(e.getMessage().contains("もう認可チェックが無い状態ではありません"), e.getMessage());
        assertTrue(e.getMessage().contains("GhostController#alreadyFixed"), e.getMessage());
    }

    @Test
    @DisplayName("認可呼び出しのあるエンドポイントは一覧に載らない")
    void 認可済みは載らない() {
        // publishing の ArticlePreviewController は requireProjectMemberOrAdmin を呼んでいる。
        Set<String> current = AuthorizationCoverageContract.currentUnauthorized(SERVICE);
        assertTrue(current.stream().noneMatch(s -> s.startsWith("ArticlePreviewController#")), current.toString());
    }

    @Test
    @DisplayName("内部ブリッジ(/api/internal/**)は対象外")
    void 内部ブリッジは対象外() {
        // publishing には CmsMediaBridgeController 等の内部ブリッジがあるが、
        // サービス間呼び出し専用でgatewayからは到達しないため一覧に載らない。
        Set<String> current = AuthorizationCoverageContract.currentUnauthorized(SERVICE);
        assertTrue(current.stream().noneMatch(s -> s.contains("BridgeController")), current.toString());
    }

    @Test
    @DisplayName("認可メソッドの派生名(requireProjectMemberOrAdminForSite等)も認可として数える")
    void 派生名も認可として数える() {
        // publishing の PostController#publish/#delete は、PostPublishService/PostDeleteService が
        // 呼ぶ requireProjectMemberOrAdminForSite にのみ守られている(issue #830)。接尾辞付きの
        // 名前を認可呼び出しと認識できないと、この2件が「認可なし」として現れてしまう。
        Set<String> publishing = AuthorizationCoverageContract.currentUnauthorized("publishing");
        assertTrue(publishing.isEmpty(), "publishing に無認可エンドポイントが残っている: " + publishing);
    }
}
