package com.letsblog.content.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedMarkerTest {

    private static boolean standalone(String text, String tag) {
        int start = text.indexOf(tag);
        return EmbedMarker.isStandalone(text, start, start + tag.length());
    }

    @Test
    void isStandalone_行頭から行末までタグだけなら真() {
        assertTrue(standalone("[x]", "[x]"));
        assertTrue(standalone("前\n[x]\n後", "[x]"));
        assertTrue(standalone("前\n[x]", "[x]"));
        assertTrue(standalone("[x]\n後", "[x]"));
    }

    @Test
    void isStandalone_前後の空白とCRLFは無視する() {
        assertTrue(standalone("前\n  \t[x] \t \r\n後", "[x]"));
    }

    @Test
    void isStandalone_同じ行に他の文字があれば偽() {
        assertFalse(standalone("前[x]", "[x]"));
        assertFalse(standalone("[x]後", "[x]"));
        assertFalse(standalone("前 [x] 後", "[x]"));
    }

    @Test
    void wrap_区切りの有無と種別を指定できる() {
        assertEquals("<!-- lbs:embed {\"type\":\"T\",\"data\":{}} -->\n\nH\n\n<!-- /lbs:embed -->",
                EmbedMarker.wrap("T", Map.of(), "H", true));
        assertEquals("<!-- lbs:embed {\"type\":\"T\",\"data\":{}} -->H<!-- /lbs:embed -->",
                EmbedMarker.wrap("T", Map.of(), "H", false));
        assertEquals("<!-- lbs:embed {\"type\":\"T\",\"data\":{}} -->\nH\n<!-- /lbs:embed -->",
                EmbedMarker.wrap("T", Map.of(), "H", "\n"));
    }

    @Test
    void wrap_文字列の中の危険な記号だけをエスケープし_配列の構造は保つ() {
        String marked = EmbedMarker.wrap("T", Map.of("v", java.util.List.of("a-->b<c>[d]")), "H", false);

        String json = marked.substring("<!-- lbs:embed ".length(), marked.indexOf(" -->"));
        assertTrue(json.contains("[\""), json);
        assertFalse(json.contains("-"));
        assertFalse(json.contains("<"));
        assertFalse(json.contains(">"));
        assertTrue(json.contains("a\\u002D\\u002D\\u003Eb\\u003Cc\\u003E\\u005Bd\\u005D"), json);
    }
}
