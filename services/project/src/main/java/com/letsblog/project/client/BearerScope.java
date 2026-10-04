package com.letsblog.project.client;

import java.util.function.Supplier;

/**
 * リクエストの外(非同期の同期処理)で、取り置いたBearerトークンをサービス間呼び出しに使うための範囲
 * (issue #1558)。サービス間ブリッジは通常、現在のリクエストの{@code Authorization}ヘッダーを転送するが、
 * 非同期のスレッドには現在のリクエストが無い。{@link #call}の中では{@link #current()}がそのトークンを返し、
 * ブリッジはリクエストよりこちらを優先して使う。
 */
public final class BearerScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private BearerScope() {
    }

    /** 範囲の中ならそのトークン、外ならnull。 */
    public static String current() {
        return CURRENT.get();
    }

    public static <T> T call(String bearer, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(bearer);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
