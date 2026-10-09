#!/usr/bin/env python3
"""system_settings に SQL で直接書く値を、`CredentialCipher` と同じ形式で暗号化して16進で出力する(#1567)。

    APP_ENCRYPTION_KEY=<base64の32バイト> python3 scripts/e2e_encrypt_setting.py <平文>

形式は `packages/lbs-common/.../CredentialCipher.java` と同じ: AES-256-GCM、先頭12バイトが IV、
続けて 暗号文 + 認証タグ(128bit)。MySQL では `UNHEX('<出力>')` で BLOB にできる。
受け入れテスト前処理 `scripts/e2e-clear-llm-db-overrides.sh` が、スタブの URL を DB へ投入するために使う。
"""

import base64
import os
import sys

IV_LENGTH_BYTES = 12


def encrypt_hex(base64_key, plain_text):
    """`CredentialCipher#encrypt` と同じ並び(IV + 暗号文 + タグ)を16進文字列で返す。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    key = base64.b64decode(base64_key)
    if len(key) != 32:
        raise ValueError("APP_ENCRYPTION_KEY は32バイト(Base64エンコード)である必要があります")
    iv = os.urandom(IV_LENGTH_BYTES)
    return (iv + AESGCM(key).encrypt(iv, plain_text.encode("utf-8"), None)).hex()


def decrypt_hex(base64_key, hex_value):
    """`encrypt_hex` の逆。IV + 暗号文 + タグの16進文字列から平文を返す(#1703、事前検査が使う)。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    key = base64.b64decode(base64_key)
    raw = bytes.fromhex(hex_value)
    return AESGCM(key).decrypt(raw[:IV_LENGTH_BYTES], raw[IV_LENGTH_BYTES:], None).decode("utf-8")


def main(argv):
    if len(argv) != 1:
        print("使い方: APP_ENCRYPTION_KEY=... e2e_encrypt_setting.py <平文>", file=sys.stderr)
        return 2
    key = os.environ.get("APP_ENCRYPTION_KEY", "")
    if not key:
        print("エラー: APP_ENCRYPTION_KEY が未設定です", file=sys.stderr)
        return 1
    print(encrypt_hex(key, argv[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
