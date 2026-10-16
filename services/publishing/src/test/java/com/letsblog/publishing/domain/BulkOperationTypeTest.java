package com.letsblog.publishing.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一括操作の種別ごとの性質(issue #1816)。全種別について、zipの受け付けとマスター環境への限定を確かめる。 */
class BulkOperationTypeTest {

    private static final Set<BulkOperationType> ZIP_UPLOADABLE =
            EnumSet.of(BulkOperationType.PLUGIN_INSTALL, BulkOperationType.THEME_INSTALL);
    private static final Set<BulkOperationType> MASTER_ONLY = EnumSet.of(BulkOperationType.CATEGORY_CREATE,
            BulkOperationType.CATEGORY_EDIT, BulkOperationType.TAG_CREATE, BulkOperationType.TAG_EDIT);

    @ParameterizedTest
    @EnumSource(BulkOperationType.class)
    void zipのアップロードを受け付けるのはプラグインとテーマのインストールだけ(BulkOperationType type) {
        assertEquals(ZIP_UPLOADABLE.contains(type), type.supportsZipUpload(), type.name());
    }

    @ParameterizedTest
    @EnumSource(BulkOperationType.class)
    void マスター環境に限るのはカテゴリとタグの作成と編集だけ(BulkOperationType type) {
        assertEquals(MASTER_ONLY.contains(type), type.requiresMasterEnvironment(), type.name());
    }
}
