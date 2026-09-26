package com.letsblog.publishing.cms;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * メディアの内容ハッシュ(sha256)をWordPress側のpost metaへ記録・照会するための共通定義(issue #1432)。
 * アップロード時に記録した値で既存メディアを同定し、同一内容の画像の重複アップロードを避ける。
 */
public final class MediaContentHash {

    /** メディア(添付ファイル)のpost metaキー。 */
    public static final String META_KEY = "_letsblog_sha256";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private MediaContentHash() {
    }

    /** バイト列のsha256を小文字16進で返す。 */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    /** 小文字16進64桁か。シェル/PHPへ埋め込む値は、必ずこれで検証したものだけにする。 */
    public static boolean isValid(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }
}
