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

    @Test
    @DisplayName("注釈の上に置いた「認可不要:」マーカーが、そのメソッド自身の除外として効く")
    void マーカーは直前のメソッドへずれない() {
        // issue #830: @XxxMapping 区切りで分割していたため、注釈の上に書いた Javadoc が
        // 「1つ手前のエンドポイントのブロック末尾」に入り、除外理由が別のメソッドに帰属していた。
        // media の ComfyUiCheckpointController#install / #delete は2件とも Javadoc に
        // マーカーを持つ。ずれが起きると後ろの1件だけが無認可として残る。
        Set<String> media = AuthorizationCoverageContract.currentUnauthorized("media");
        assertTrue(media.stream().noneMatch(e -> e.startsWith("ComfyUiCheckpointController#")),
                "ComfyUiCheckpointController のマーカーが効いていない: " + media);
    }

    @Test
    @DisplayName("コメント本文にパス(/api/render/**)を含んでもマーカーが効く")
    void コメント内のスラッシュアスタリスクで開始位置を誤らない() {
        // issue #830: lastIndexOf("/*") が本文中の "/api/render/**" の "/*" を拾い、
        // コメントの途中をブロック開始と誤認していた。その結果、先頭行に書いた
        // 「認可不要:」がブロックの外へ落ちて除外が効かなかった。
        Set<String> media = AuthorizationCoverageContract.currentUnauthorized("media");
        assertTrue(media.stream().noneMatch(e -> e.startsWith("RenderController#")),
                "RenderController のマーカーが効いていない: " + media);
    }

    @Test
    @DisplayName("同じコントローラ内のprivateヘルパが認可していれば「認可あり」と数える")
    void コントローラ内のヘルパ経由の認可も数える() {
        // issue #830: 「IDで引いて所属プロジェクトのメンバーか確かめる」処理は
        // ハンドラ間で共通化するのが自然(media の DiagramController#findAuthorized 等)。
        // ヘルパを見ないと、実際には守られているエンドポイントを未認可と誤判定する。
        Set<String> media = AuthorizationCoverageContract.currentUnauthorized("media");
        assertTrue(media.stream().noneMatch(e -> e.startsWith("DiagramController#")),
                "DiagramController のヘルパ経由の認可が効いていない: " + media);
        assertTrue(media.stream().noneMatch(e -> e.startsWith("GeneratedImageController#")),
                "GeneratedImageController のヘルパ経由の認可が効いていない: " + media);
    }
}
