package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** `wp letsblog preview` の出力から署名付きプレビュー URL を取り出す(issue #1561)。 */
class SignedPreviewTest {

    @Test
    void 出力のurlと期限を取り出す() {
        SignedPreview preview = SignedPreview.fromPreviewOutput(
                "{\"url\":\"https://example.com/?letsblog_preview=abc\",\"expires_at\":1800000600}\n");

        assertEquals("https://example.com/?letsblog_preview=abc", preview.url());
        assertEquals(1800000600L, preview.expiresAt());
    }

    @Test
    void 出力が解釈できなければ例外() {
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput(null));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("Success"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("[1]"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("{\"expires_at\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("{\"url\":\"\",\"expires_at\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("{\"url\":5,\"expires_at\":1}"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("{\"url\":\"https://x\"}"));
        assertThrows(IllegalArgumentException.class, () -> SignedPreview.fromPreviewOutput("{\"url\":\"https://x\",\"expires_at\":\"soon\"}"));
    }
}
