package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * カスタムタグの保存・更新・削除を検知して同期を依頼する(issue #1558)。プロジェクトのタグならそのプロジェクトの
 * サイトへ、グローバルタグ(projectId=null)ならすべてのプロジェクトのサイトへ。
 */
@ExtendWith(MockitoExtension.class)
class CustomTagChangeListenerTest {

    @Mock
    private LetsblogSyncNotifier notifier;

    private CustomTag tag(Long projectId) {
        CustomTag tag = new CustomTag();
        tag.setTagName("card");
        tag.setProjectId(projectId);
        return tag;
    }

    @Test
    void プロジェクトのタグの作成はそのプロジェクトを依頼する() {
        new CustomTagChangeListener(notifier).onChange(tag(5L));

        verify(notifier).notifyProjectChanged(5L);
        verifyNoMoreInteractions(notifier);
    }

    @Test
    void グローバルタグの変更はすべてのプロジェクトを依頼する() {
        new CustomTagChangeListener(notifier).onChange(tag(null));

        verify(notifier).notifyGlobalChanged();
        verifyNoMoreInteractions(notifier);
    }
}
