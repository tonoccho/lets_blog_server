package com.letsblog.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * ホストのタイムゾーンに関係なく、テストJVMがUTCで動くこと(#1257)。
 * ルートの build.gradle が全サブプロジェクトの Test タスクへ TZ と user.timezone を与える。
 */
class DefaultTimeZoneTest {

    @Test
    void jvmDefaultTimeZoneIsUtc() {
        assertEquals(ZoneOffset.UTC, ZoneId.systemDefault().getRules().getOffset(java.time.Instant.now()));
        assertEquals("UTC", TimeZone.getDefault().getID());
    }
}
