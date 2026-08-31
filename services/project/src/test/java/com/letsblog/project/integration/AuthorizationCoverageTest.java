package com.letsblog.project.integration;

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
    private static final Set<String> KNOWN_UNAUTHORIZED = Set.of(
            // 残る2件はいずれも「一覧」。単一リソースの参照(ProjectController#get /
            // SiteController#getDetail)は #830 で閉じたが、一覧は「自分がアクセスできる分だけ返す」
            // 絞り込みが要る。その判定材料である project_users は legacy-api に残っており
            // (ADR-0004 によりクロススキーマ参照不可)、内部ブリッジ越しの N+1 になる。
            // VSCode 拡張が SiteController#list をサイト選択に使っている(extension/src/apiClient.ts)ため、
            // admin 限定にすると非 admin の拡張利用が壊れる。#583 で project_users が
            // project-service へ移った後に、リポジトリ側の絞り込みとして実装する。
            "ProjectController#list",
            "SiteController#list");

    @Test
    @DisplayName("認可チェックの無いエンドポイントが増えていない(issue #830)")
    void 認可チェックの無いエンドポイントが増えていない() {
        AuthorizationCoverageContract.verifyNoNewUnauthorizedEndpoints("project", KNOWN_UNAUTHORIZED);
    }
}
