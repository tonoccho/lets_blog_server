package com.letsblog.publishing.aop;

import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.service.PluginThemeComparisonService;
import com.letsblog.publishing.service.PostComparisonService;
import com.letsblog.publishing.service.TermComparisonService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * issue #1137: 一括削除(カテゴリ/タグ/プラグイン/テーマ/投稿の {@code delete-all})5本が
 * 監査ログを1件も残していなかった欠陥の回帰テスト。
 *
 * <p>この5メソッドは実際に「削除対象が全環境で1件も見つからない」場合に
 * {@link IllegalArgumentException} で失敗する({@link TermComparisonService}の
 * private {@code deleteEverywhere}参照)。実環境(WordPressサイト)にカテゴリ/タグ/
 * プラグイン/テーマ/投稿が実在しないと成功パスへ到達できないため、この一括削除5本を
 * 実際に成功させるE2Eシナリオはこのリポジトリのどの受け入れテストにも存在しない
 * (issue #941 / AT-15ですら、既に記録される{@code POST_PUBLISHED}/{@code POST_DELETED}
 * にe2eの検証を持たない)。そのため、ここでは{@link AuditLogAspect}が実際に発火する
 * 保証(既存の{@code @AuditLog}運用実績)とは別に、対象5メソッドに正しい
 * {@code @AuditLog}(action/resourceType)が付与されていることをリフレクションで検証する
 * (documented exception。CLAUDE.md「Where the tests live」参照)。
 */
class BulkDeleteAuditLogAnnotationTest {

    private Method method(Class<?> owner, String name, Class<?>... paramTypes) throws NoSuchMethodException {
        return owner.getMethod(name, paramTypes);
    }

    private AuditLog auditLogOf(Method method) {
        AuditLog annotation = method.getAnnotation(AuditLog.class);
        assertNotNull(annotation, method + " に@AuditLogが付与されていません");
        return annotation;
    }

    @Test
    void deleteCategoryEverywhere_CATEGORY_BULK_DELETEDを記録する() throws NoSuchMethodException {
        Method target = method(TermComparisonService.class, "deleteCategoryEverywhere", Long.class, String.class, Long.class);
        AuditLog annotation = auditLogOf(target);
        assertEquals(AuditLogAction.CATEGORY_BULK_DELETED, annotation.action());
        assertEquals("CATEGORY", annotation.resourceType());
    }

    @Test
    void deleteTagEverywhere_TAG_BULK_DELETEDを記録する() throws NoSuchMethodException {
        Method target = method(TermComparisonService.class, "deleteTagEverywhere", Long.class, String.class, Long.class);
        AuditLog annotation = auditLogOf(target);
        assertEquals(AuditLogAction.TAG_BULK_DELETED, annotation.action());
        assertEquals("TAG", annotation.resourceType());
    }

    @Test
    void deletePluginEverywhere_PLUGIN_BULK_DELETEDを記録する() throws NoSuchMethodException {
        Method target = method(PluginThemeComparisonService.class, "deletePluginEverywhere", Long.class, String.class, Long.class);
        AuditLog annotation = auditLogOf(target);
        assertEquals(AuditLogAction.PLUGIN_BULK_DELETED, annotation.action());
        assertEquals("PLUGIN", annotation.resourceType());
    }

    @Test
    void deleteThemeEverywhere_THEME_BULK_DELETEDを記録する() throws NoSuchMethodException {
        Method target = method(PluginThemeComparisonService.class, "deleteThemeEverywhere", Long.class, String.class, Long.class);
        AuditLog annotation = auditLogOf(target);
        assertEquals(AuditLogAction.THEME_BULK_DELETED, annotation.action());
        assertEquals("THEME", annotation.resourceType());
    }

    @Test
    void deleteEverywhere_投稿はPOST_BULK_DELETEDを記録する() throws NoSuchMethodException {
        Method target = method(PostComparisonService.class, "deleteEverywhere", Long.class, String.class, String.class, Long.class);
        AuditLog annotation = auditLogOf(target);
        assertEquals(AuditLogAction.POST_BULK_DELETED, annotation.action());
        assertEquals("POST", annotation.resourceType());
    }
}
