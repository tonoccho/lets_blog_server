package com.letsblog.common.net;

/** 宛先として常に拒否するアドレスの判定(issue #1518。issue #1547で保存時・接続時の共通部品にした)。 */
public final class DestinationAddressRules {

    private DestinationAddressRules() {
    }

    /**
     * 4バイト(IPv4)または16バイト(IPv6)のアドレスが拒否対象か。
     * loopback・未指定・169.254/16・IPv6リンクローカル・fd00:ec2::254。IPv4射影(::ffff:a.b.c.d)はIPv4として判定する。
     */
    public static boolean isDeniedAddress(byte[] address) {
        byte[] a = unmapIpv4(address);
        if (a.length == 4) {
            int first = a[0] & 0xff;
            boolean unspecified = a[0] == 0 && a[1] == 0 && a[2] == 0 && a[3] == 0;
            return first == 127 || unspecified || (first == 169 && (a[1] & 0xff) == 254);
        }
        boolean allZeroButLast = true;
        for (int i = 0; i < 15; i++) {
            allZeroButLast &= a[i] == 0;
        }
        boolean unspecifiedOrLoopback = allZeroButLast && (a[15] == 0 || a[15] == 1);
        boolean linkLocal = (a[0] & 0xff) == 0xfe && (a[1] & 0xc0) == 0x80;
        boolean ec2Metadata = (a[0] & 0xff) == 0xfd && a[1] == 0x00 && a[2] == 0x0e && (a[3] & 0xff) == 0xc2
                && isZero(a, 4, 14) && a[14] == 0x02 && (a[15] & 0xff) == 0x54;
        return unspecifiedOrLoopback || linkLocal || ec2Metadata;
    }

    /** IPv4射影アドレス(::ffff:a.b.c.d)なら後ろ4バイト、そうでなければそのまま。 */
    static byte[] unmapIpv4(byte[] a) {
        boolean mapped = a.length == 16 && isZero(a, 0, 10) && (a[10] & 0xff) == 0xff && (a[11] & 0xff) == 0xff;
        return mapped ? new byte[] {a[12], a[13], a[14], a[15]} : a;
    }

    private static boolean isZero(byte[] a, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
