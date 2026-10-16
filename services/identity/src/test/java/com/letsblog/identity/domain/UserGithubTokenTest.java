package com.letsblog.identity.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link User#hasGithubToken()}: 未設定(null)・空のバイト列・設定済みを区別する。 */
class UserGithubTokenTest {

    @Test
    void 未設定ならfalse() {
        assertFalse(new User().hasGithubToken());
    }

    @Test
    void 空のバイト列でもfalse() {
        User user = new User();
        user.setGithubTokenEncrypted(new byte[0]);

        assertFalse(user.hasGithubToken());
    }

    @Test
    void バイト列があればtrue() {
        User user = new User();
        user.setGithubTokenEncrypted(new byte[] {1});

        assertTrue(user.hasGithubToken());
    }
}
