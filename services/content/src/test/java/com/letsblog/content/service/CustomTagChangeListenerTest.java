package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * カスタムタグの保存・更新・削除を検知して同期を依頼する(issue #1558)。プロジェクトのタグならそのプロジェクトの
 * サイトへ、グローバルタグ(projectId=null)ならすべてのプロジェクトのサイトへ。
 *
 * <p>issue #1821: LetsblogSyncNotifierは実物で、依頼先のproject-serviceをHTTPフェイクにして、実際に届いた依頼を見る。
 */
class CustomTagChangeListenerTest {

    private static final String SYNC_PATH = "/api/internal/project/letsblog-sync";

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private CustomTag tag(Long projectId) {
        CustomTag tag = new CustomTag();
        tag.setTagName("card");
        tag.setProjectId(projectId);
        return tag;
    }

    @Test
    void プロジェクトのタグの作成はそのプロジェクトを依頼する() {
        world.customTagChangeListener.onChange(tag(5L));

        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertEquals(5, world.bodyOf(requests.get(0)).get("projectId").asInt());
    }

    @Test
    void グローバルタグの変更はすべてのプロジェクトを依頼する() {
        world.customTagChangeListener.onChange(tag(null));

        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertTrue(world.bodyOf(requests.get(0)).get("projectId").isNull());
    }
}
