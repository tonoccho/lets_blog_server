package com.letsblog.common.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 作業ログ(BulkOperationLog)に、失敗時の例外メッセージだけでなくフルスタックトレースも
 * 保存できるようにするための変換ヘルパー。
 */
public final class StackTraceUtil {

    private StackTraceUtil() {
    }

    public static String toString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
