package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/**
 * カスタムタグの保存・更新・削除を検知して、WordPress のサイトへの同期を依頼する(issue #1558)。
 * 作成・更新・削除のどの経路(手動作成・テンプレート適用・AI生成)でも漏れないよう、サービスごとではなく
 * エンティティの変更で検知する。プロジェクトのタグならそのプロジェクトのサイトへ、グローバルタグ
 * (projectId=null)ならすべてのプロジェクトのサイトへ送る。
 */
@Component
public class CustomTagChangeListener {

    private final LetsblogSyncNotifier notifier;

    public CustomTagChangeListener(LetsblogSyncNotifier notifier) {
        this.notifier = notifier;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void onChange(CustomTag tag) {
        if (tag.getProjectId() == null) {
            notifier.notifyGlobalChanged();
        } else {
            notifier.notifyProjectChanged(tag.getProjectId());
        }
    }
}
