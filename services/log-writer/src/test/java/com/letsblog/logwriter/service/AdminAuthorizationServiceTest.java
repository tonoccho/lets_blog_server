package com.letsblog.logwriter.service;

import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** AdminAuthorizationService(issue #1829で書き直し)。CurrentActorService・IdentityClient は実物で、identity-service は HTTP のフェイク。 */
class AdminAuthorizationServiceTest extends LogWriterIntegrationTestBase {

    @Autowired
    private AdminAuthorizationService service;

    @Test
    void requireAdmin_adminなら例外を投げない() {
        actor("admin-token", 1L, "admin");
        asBearer("admin-token");

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        actor("user-token", 2L, "editor");
        asBearer("user-token");

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireAdmin_未認証はForbidden() {
        asBearer(null);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireAdmin_identityService障害はForbiddenへ縮退させず伝播する() {
        HTTP.status("GET", "/api/identity/me", 404, "{}");
        asBearer("admin-token");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.requireAdmin());
    }
}
