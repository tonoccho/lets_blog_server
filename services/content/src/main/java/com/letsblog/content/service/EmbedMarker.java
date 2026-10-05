package com.letsblog.content.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 組み込みタグ(ブログカード・Amazon・目次)の展開HTMLを、WordPressプラグインが表示時に
 * 同期済みのデザインテンプレートで展開し直すための目印(HTMLコメント)で挟む(issue #1563)。
 * カスタムタグの目印(CustomTagRenderServiceの`lbs:tag`、issue #1560)と同じ仕組みで、
 * 開始コメントに種別と取得したデータをJSONで持ち、その後ろに投稿時点の展開HTMLを置く。
 * コメントなのでプラグインがなくても画面に出ず、展開HTMLがそのまま表示される。
 */
final class EmbedMarker {

    private static final String BLOCK_SEPARATOR = "\n\n";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.getFactory().setCharacterEscapes(new MarkerEscapes());
    }

    private EmbedMarker() {
    }

    /** Markdown変換前のタグ用。 */
    static String wrap(String type, Map<String, ?> data, String html, boolean block) {
        return wrap(type, data, html, block ? BLOCK_SEPARATOR : "");
    }

    /**
     * 行を独占するタグ(block)は、Markdown変換で目印のコメントと展開HTMLが別のHTMLブロックになるよう
     * 前後に空行を入れる。文中のタグは段落を割らないよう区切りなしで挟む。
     * separatorは目印のコメントと展開HTMLの間に入れる文字列。
     */
    static String wrap(String type, Map<String, ?> data, String html, String separator) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", type);
        marker.put("data", data);
        return "<!-- lbs:embed " + json(marker) + " -->" + separator + html + separator + "<!-- /lbs:embed -->";
    }

    /** `[tag ...]`が行を独占している(前後が行頭・行末。空白は無視する)か。 */
    static boolean isStandalone(String text, int start, int end) {
        int before = start;
        while (before > 0 && isBlank(text.charAt(before - 1))) {
            before--;
        }
        int after = end;
        while (after < text.length() && isBlank(text.charAt(after))) {
            after++;
        }
        return (before == 0 || text.charAt(before - 1) == '\n') && (after == text.length() || text.charAt(after) == '\n'
                || text.charAt(after) == '\r');
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * 文字列の中の、コメントを閉じる`-->`、HTMLとして解釈される`<` `>`、後続の組み込みタグ展開が拾う`[` `]`を、
     * JSONのunicodeエスケープに置き換える。JSONとしては同じ内容のまま読める。
     * 文字列の外のJSONの構造(配列の`[` `]`)はそのまま残る。
     */
    static String json(Map<String, Object> marker) {
        try {
            return OBJECT_MAPPER.writeValueAsString(marker);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("組み込みタグの目印をJSONにできません", e);
        }
    }

    private static final class MarkerEscapes extends CharacterEscapes {
        private final int[] asciiEscapes = CharacterEscapes.standardAsciiEscapesForJSON();

        MarkerEscapes() {
            for (char c : new char[] {'-', '<', '>', '[', ']'}) {
                asciiEscapes[c] = CharacterEscapes.ESCAPE_STANDARD;
            }
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return null;
        }
    }
}
