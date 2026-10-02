package com.letsblog.ai.repository;

import com.letsblog.ai.domain.ArticlePlanSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ArticlePlanSessionRepositoryの照会メソッドのソート順の回帰テスト(issue #1375)。
 *
 * <p>article_plan_sessions.updated_atは秒精度(V1__create_ai_tables.sqlのDATETIME列)のため、
 * updatedAt単独のソートでは同一秒に更新された複数セッションの順序がDB任せになる。
 * #1332と同じくIDを第2キー(降順)にして順序を一意に決める。
 *
 * <p>このモジュールにはDB付きのテスト基盤が無いため、Spring Dataが派生クエリ名から
 * 組み立てるソート({@link PartTree#getSort()})を直接検証する。
 */
class ArticlePlanSessionRepositoryTest {

    private static final Sort UPDATED_AT_DESC_THEN_ID_DESC =
            Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    @Test
    void 一覧取得はupdatedAt降順_ID降順で並ぶ() {
        assertEquals(UPDATED_AT_DESC_THEN_ID_DESC, sortOf("findByProjectId"));
    }

    @Test
    void issue番号に紐づく最新取得はupdatedAt降順_ID降順で先頭を返す() {
        assertEquals(UPDATED_AT_DESC_THEN_ID_DESC, sortOf("findFirstByProjectIdAndGithubIssueNumber"));
    }

    private static Sort sortOf(String prefix) {
        List<Method> candidates = Arrays.stream(ArticlePlanSessionRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith(prefix + "OrderBy"))
                .toList();
        assertFalse(candidates.isEmpty(), prefix + "OrderBy... の照会メソッドが無い");
        return new PartTree(candidates.get(0).getName(), ArticlePlanSession.class).getSort();
    }
}
