package com.letsblog.api.controller;

import com.letsblog.api.service.ProjectUserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/** ProjectUserBridgeControllerの回帰テスト(issue #577 stage2)。 */
@ExtendWith(MockitoExtension.class)
class ProjectUserBridgeControllerTest {

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    private ProjectUserBridgeController controller() {
        return new ProjectUserBridgeController(projectUserSyncService);
    }

    @Test
    void reconcileRolesForSite_サービスへ委譲する() {
        ResponseEntity<Void> response = controller().reconcileRolesForSite(1L, 2L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).reconcileRolesForSite(1L, 2L);
    }
}
