package com.letsblog.project.controller;

import com.letsblog.project.service.LetsblogSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/** content-service が、タグ・CSS・プレフィックスの変更をサイトへの同期として依頼する内部API(issue #1558)。 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncInternalControllerTest {

    @Mock
    private LetsblogSyncService letsblogSyncService;

    @Test
    void プロジェクトIDがあればそのプロジェクトの同期を依頼して202を返す() {
        var response = new LetsblogSyncInternalController(letsblogSyncService)
                .request(new LetsblogSyncInternalController.SyncRequest(7L));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(letsblogSyncService).requestProjectSync(7L);
    }

    @Test
    void プロジェクトIDが無ければすべてのプロジェクトの同期を依頼する() {
        var response = new LetsblogSyncInternalController(letsblogSyncService)
                .request(new LetsblogSyncInternalController.SyncRequest(null));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(letsblogSyncService).requestAllSync();
    }
}
