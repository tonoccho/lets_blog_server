package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** WordPressのsanitize_titleが保存するpost_nameの形への正規化(issue #1431)。 */
class WordPressSlugTest {

    @Test
    void ASCIIは小文字化し空白をハイフンにする() {
        assertEquals("hello-world", WordPressSlug.sanitizeTitle("Hello World"));
    }

    @Test
    void 非ASCIIは小文字のパーセントエンコードにする() {
        assertEquals("%e6%97%a5%e6%9c%ac%e8%aa%9e", WordPressSlug.sanitizeTitle("日本語"));
    }

    @Test
    void 使えない記号は取り除き連続ハイフンは1つにして前後を落とす() {
        assertEquals("a-b", WordPressSlug.sanitizeTitle("--a!!  b--"));
    }

    @Test
    void ドットはハイフンにしスラッシュは取り除く() {
        assertEquals("v1-2", WordPressSlug.sanitizeTitle("v1.2"));
        assertEquals("ab", WordPressSlug.sanitizeTitle("a/b"));
    }

    @Test
    void 既にパーセントエンコード済みの値は保つ() {
        assertEquals("%e6%97%a5", WordPressSlug.sanitizeTitle("%E6%97%A5"));
    }

    @Test
    void アンダースコアは保つ() {
        assertEquals("a_b", WordPressSlug.sanitizeTitle("a_b"));
    }

    @Test
    void nullや空は空文字にする() {
        assertEquals("", WordPressSlug.sanitizeTitle(null));
        assertEquals("", WordPressSlug.sanitizeTitle("   "));
    }
}
