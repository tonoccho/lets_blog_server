package com.letsblog.identity.messaging;

import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import com.letsblog.identity.service.ProjectUserSyncService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/** issue #1324: project.environment-boundを受けて、既存メンバーの補填を依頼する。 */
@ExtendWith(MockitoExtension.class)
class EventMessageListenerTest {

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    @Test
    void onProjectEnvironmentBound_イベントのprojectIdとsiteIdで補填を依頼する() {
        EventMessageListener listener = new EventMessageListener(projectUserSyncService);

        listener.onProjectEnvironmentBound(new ProjectEnvironmentBoundEvent("evt-1", Instant.now(), 7L, 30L));

        verify(projectUserSyncService).backfillMembersToSite(7L, 30L);
    }
}
