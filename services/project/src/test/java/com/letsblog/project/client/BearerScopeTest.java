package com.letsblog.project.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** リクエストの外(非同期の同期処理)でも、取り置いたトークンでサービス間呼び出しをするための範囲(issue #1558)。 */
class BearerScopeTest {

    @Test
    void 範囲の中だけトークンが見える() {
        assertNull(BearerScope.current());

        String inside = BearerScope.call("Bearer x", BearerScope::current);

        assertEquals("Bearer x", inside);
        assertNull(BearerScope.current());
    }

    @Test
    void 例外でも範囲を抜けたら元に戻る() {
        assertThrows(IllegalStateException.class, () -> BearerScope.call("Bearer x", () -> {
            throw new IllegalStateException("boom");
        }));

        assertNull(BearerScope.current());
    }

    @Test
    void 入れ子では内側の範囲を抜けると外側のトークンに戻る() {
        String after = BearerScope.call("Bearer outer", () -> {
            BearerScope.call("Bearer inner", BearerScope::current);
            return BearerScope.current();
        });

        assertEquals("Bearer outer", after);
    }
}
