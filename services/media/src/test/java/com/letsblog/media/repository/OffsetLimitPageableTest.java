package com.letsblog.media.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1472: 任意のoffsetを持てるPageableの振る舞い。 */
class OffsetLimitPageableTest {

    @Test
    void offsetとlimitをそのまま返す() {
        OffsetLimitPageable pageable = new OffsetLimitPageable(7, 5);

        assertEquals(7, pageable.getOffset());
        assertEquals(5, pageable.getPageSize());
        assertEquals(1, pageable.getPageNumber());
        assertTrue(pageable.getSort().isUnsorted());
    }

    @Test
    void 負のoffsetと1未満のlimitは拒否する() {
        assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageable(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new OffsetLimitPageable(0, 0));
    }

    @Test
    void nextはlimit件ぶん進む() {
        Pageable next = new OffsetLimitPageable(7, 5).next();

        assertEquals(12, next.getOffset());
        assertEquals(5, next.getPageSize());
    }

    @Test
    void 先頭ではpreviousOrFirstも先頭のまま() {
        OffsetLimitPageable pageable = new OffsetLimitPageable(0, 5);

        assertFalse(pageable.hasPrevious());
        assertEquals(0, pageable.previousOrFirst().getOffset());
    }

    @Test
    void 先頭以外ではpreviousOrFirstはlimit件戻り0を下回らない() {
        assertTrue(new OffsetLimitPageable(7, 5).hasPrevious());
        assertEquals(2, new OffsetLimitPageable(7, 5).previousOrFirst().getOffset());
        assertEquals(0, new OffsetLimitPageable(3, 5).previousOrFirst().getOffset());
    }

    @Test
    void firstとwithPageはページ番号かけるlimitの位置になる() {
        OffsetLimitPageable pageable = new OffsetLimitPageable(7, 5);

        assertEquals(0, pageable.first().getOffset());
        assertEquals(15, pageable.withPage(3).getOffset());
    }
}
