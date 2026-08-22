package com.letsblog.api.markdown;

/** {@link MarkdownTableParser}が入力を解析できなかった場合に投げる。 */
public class MarkdownTableParseException extends RuntimeException {
    public MarkdownTableParseException(String message) {
        super(message);
    }
}
