package com.letsblog.publishing.cms;

import java.nio.charset.StandardCharsets;

/**
 * WordPressの{@code sanitize_title}(sanitize_title_with_dashes)が{@code post_name}へ保存する形への
 * 正規化(issue #1431)。送信側のスラッグは生の日本語でありうる一方、WordPressは非ASCIIを
 * 小文字のパーセントエンコードで保存するため、照会の比較の前に同じ形へそろえる。
 * WordPressの文字数上限(200バイト)による切り詰めは再現しない。
 */
public final class WordPressSlug {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WordPressSlug() {
    }

    /** 正規化した値を返す。null・空白のみ・使える文字が残らない場合は空文字を返す。 */
    public static String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : raw.trim().getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xff;
            if (v >= 0x80) {
                sb.append('%').append(HEX[v >> 4]).append(HEX[v & 0x0f]);
            } else {
                sb.append((char) v);
            }
        }
        String lowered = sb.toString().toLowerCase(java.util.Locale.ROOT)
                .replace('.', '-')
                .replace("/", "")
                .replaceAll("[^%a-z0-9 _-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
        return lowered.replaceAll("^-+|-+$", "");
    }
}
